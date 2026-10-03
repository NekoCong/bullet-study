package com.nekocong.bulletstudy.sim;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.nekocong.bulletstudy.redis.RedisFanoutPublisher;

/**
 * 服务端模拟器：在本进程内打开 N 条<b>真实</b> WebSocket 连接回连自身，按聚合速率灌入弹幕。
 *
 * <p><b>端到端链路中的位置：</b>它是“压力源”，验证整条链路（连接 → 解析 → Redis → 扇出 → 下发）：
 * <pre>
 *   POST /api/sim/start
 *        ▼
 *   JDK HttpClient.newWebSocketBuilder() ── 打开 N 条真实 socket ──▶ 本应用 /ws/danmaku
 *        │                                                              │
 *        │  共享单线程调度器按聚合速率 sendText                          ▼
 *        └────────────────────────────────────────────▶ 与浏览器客户端完全相同的处理链路
 * </pre>
 *
 * <p><b>为什么用 JDK HttpClient 而不是 Spring 的 StandardWebSocketClient：</b>
 * JDK 11+ 自带的 {@code java.net.http.HttpClient.newWebSocketBuilder()} 支持<b>异步回调</b>
 * （{@code buildAsync} 返回 {@code CompletableFuture}），不需要为每条连接分配线程；
 * 用同步客户端开 1000 条连接就得 1000 个线程，直接压垮 JVM。这里刻意只用 JDK 内置能力，
 * 不引入额外依赖（符合 Must-NOT 约束）。
 *
 * <p><b>并发模型（关键）：</b>
 * <ul>
 *   <li><b>连接建立</b>：全部走异步，回调线程由 HttpClient 内部管理，回调只做“登记 + 排程”；</li>
 *   <li><b>消息发送</b>：所有连接共用一个{@link ScheduledExecutorService}（单线程）。
 *       单线程天然保证“同一 WebSocket 不会被并发 send”，无需为每个客户端加锁；
 *       同时这也是“禁用 thread-per-connection”的直接体现。</li>
 * </ul>
 *
 * <p><b>速率控制：</b>目标“聚合速率为 R 条/秒”。设连接数 N，则每条连接的发送间隔为
 * {@code interval = N * 1000 / R} 毫秒（单连接速率 R/N，N 条相加正好 R）。
 * 启动时把各连接的<b>首次</b>发送按 {@code i * 1000 / R} 毫秒错开，避免 t=0 时刻 N 条连接同时爆发
 * 造成瞬时洪峰（那会让合批队列瞬间超限、大量丢弃，污染“投递健康度”）。稳态聚合速率仍为 R。
 *
 * <p><b>失败隔离：</b>单条连接建立失败或单次发送失败都计入 {@code failed} 并继续，绝不中断整轮模拟。
 */
@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    /** 弹幕 WebSocket 端点路径。 */
    private static final String DANMAKU_PATH = "/ws/danmaku";

    private final RedisFanoutPublisher fanoutPublisher;

    /** 本应用监听端口，用于拼接回连自身的 URI。 */
    private final int serverPort;

    /** 异步 WebSocket 客户端（全进程一个即可）。 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 发送调度器：单线程守护线程池。
     * 守护线程保证它不会阻止 JVM 退出；单线程保证发送串行、无需每连接加锁。
     */
    private final ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sim-sender");
        thread.setDaemon(true);
        return thread;
    });

    /** 当前仍打开的虚拟客户端连接。 */
    private final List<WebSocket> clients = new CopyOnWriteArrayList<>();

    /** 是否有模拟正在运行（用于 409 互斥）。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 成功发送计数。 */
    private final AtomicInteger sent = new AtomicInteger(0);

    /** 失败计数（连接失败 + 发送失败）。 */
    private final AtomicInteger failed = new AtomicInteger(0);

    /**
     * 剩余工作量（未完成的“消息”条数）。所有连接的消息都发完（或被判定不可发）后归零，
     * 用于精确判断“本轮是否结束”，比“看 sent 是否达到理论值”更健壮（考虑连接失败）。
     */
    private final AtomicLong remainingWork = new AtomicLong(0);

    /** 当前轮次 id；旧轮次的残留任务据此自我取消，避免污染新一轮计数。 */
    private volatile String activeRunId;

    /**
     * 构造器注入。
     *
     * @param fanoutPublisher Redis 发布器（复用其 ping 做前置健康检查入口）
     * @param serverPort      监听端口，来自 {@code server.port}
     */
    public SimulationService(RedisFanoutPublisher fanoutPublisher,
                             @Value("${server.port:8080}") int serverPort) {
        this.fanoutPublisher = fanoutPublisher;
        this.serverPort = serverPort;
    }

    /**
     * 启动一轮模拟。
     *
     * @param request 校验后的启动参数
     * @return 本轮 run id；若已有模拟在运行（互斥失败）返回 {@code null}
     */
    public String start(SimulationRequest request) {
        if (!this.running.compareAndSet(false, true)) {
            return null;
        }
        String roomId = request.resolvedRoomId();
        int connections = request.connections();
        int perConnection = request.messagesPerConnection();
        int rate = request.ratePerSecond();

        long totalMessages = (long) connections * perConnection;
        long intervalMs = Math.max(1L, (long) Math.ceil(1000.0d * connections / rate));
        String runId = UUID.randomUUID().toString();
        this.activeRunId = runId;
        this.remainingWork.set(totalMessages);
        this.sent.set(0);
        this.failed.set(0);

        log.info("[sim] start runId={} connections={} perConnection={} rate={}/s interval={}ms room={} totalMessages={}",
                runId, connections, perConnection, rate, intervalMs, roomId, totalMessages);

        URI uri = URI.create("ws://localhost:" + this.serverPort + DANMAKU_PATH + "?roomId=" + roomId);
        for (int i = 0; i < connections; i++) {
            final int clientIndex = i;
            // 首次发送错开，避免 t=0 的 N 连发洪峰；偏移量 = i * (1000/R) 毫秒。
            long initialDelayMs = Math.round(1000.0d * i / rate);
            this.httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(uri, new NoopListener())
                    .whenComplete((webSocket, error) -> {
                        if (error != null) {
                            // 该连接打不开：它对应的 perConnection 条消息无法发送，直接从工作量中扣除。
                            this.failed.incrementAndGet();
                            this.remainingWork.addAndGet(-perConnection);
                            log.debug("[sim] connection {} failed: {}", clientIndex, error.getMessage());
                            checkComplete(runId);
                            return;
                        }
                        this.clients.add(webSocket);
                        scheduleClient(webSocket, clientIndex, perConnection, intervalMs, initialDelayMs, runId);
                    });
        }
        return runId;
    }

    /**
     * 停止当前模拟：关闭所有虚拟连接并清空计数状态。
     *
     * @return 停止后的状态快照
     */
    public SimulationStatus stop() {
        this.running.set(false);
        this.activeRunId = null;
        int closed = 0;
        for (WebSocket webSocket : this.clients) {
            try {
                // 正常关闭握手；服务端 afterConnectionClosed 会同步把在线数减掉。
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "stop");
                closed++;
            } catch (RuntimeException e) {
                log.debug("[sim] failed to close client: {}", e.getMessage());
            }
        }
        this.clients.clear();
        this.remainingWork.set(0);
        log.info("[sim] stopped, closed {} client connections", closed);
        return status();
    }

    /**
     * 读取当前状态。
     *
     * @return 运行标志、当前连接数、已发送、失败计数
     */
    public SimulationStatus status() {
        return new SimulationStatus(this.running.get(), this.clients.size(), this.sent.get(), this.failed.get());
    }

    /**
     * 为单个虚拟客户端排程它的消息发送。
     *
     * @param webSocket    已建立的连接
     * @param clientIndex  客户端序号（用于生成可读文本）
     * @param perConnection 该连接要发的消息数
     * @param intervalMs   该连接的发送间隔（等价于聚合速率 R）
     * @param initialDelayMs 首次发送延迟（错开洪峰）
     * @param runId        本轮 id（用于识别过期任务）
     */
    private void scheduleClient(WebSocket webSocket, int clientIndex, int perConnection,
                                long intervalMs, long initialDelayMs, String runId) {
        AtomicInteger remainingForClient = new AtomicInteger(perConnection);
        AtomicInteger sequence = new AtomicInteger(0);
        ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
        holder[0] = this.sender.scheduleAtFixedRate(() -> {
            // 过期轮次（被 stop 或新一轮 start 取代）的任务：立即自我取消，避免污染新计数。
            if (!runId.equals(this.activeRunId)) {
                cancel(holder);
                return;
            }
            if (remainingForClient.get() <= 0) {
                cancel(holder);
                return;
            }
            String text = "sim-" + runId.substring(0, 8) + "-" + clientIndex + "-" + sequence.incrementAndGet();
            String frame = "{\"type\":\"danmaku\",\"text\":\"" + text + "\"}";
            try {
                webSocket.sendText(frame, true);
                this.sent.incrementAndGet();
                this.remainingWork.decrementAndGet();
                if (remainingForClient.decrementAndGet() <= 0) {
                    cancel(holder);
                }
            } catch (RuntimeException e) {
                this.failed.incrementAndGet();
                this.remainingWork.decrementAndGet();
                remainingForClient.set(0);
                cancel(holder);
                log.debug("[sim] client {} send failed: {}", clientIndex, e.getMessage());
            }
            checkComplete(runId);
        }, initialDelayMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 若本轮工作量已全部了结，则把运行标志置为 false。
     *
     * @param runId 本轮 id；仅当仍是当前轮次时才允许结束，避免旧轮次误关新轮次
     */
    private void checkComplete(String runId) {
        if (!runId.equals(this.activeRunId)) {
            return;
        }
        if (this.remainingWork.get() <= 0 && this.running.compareAndSet(true, false)) {
            log.info("[sim] run {} completed: sent={} failed={}", runId, this.sent.get(), this.failed.get());
        }
    }

    /**
     * 取消定时任务（忽略未启动/已完成的边界）。
     *
     * @param holder 持有 ScheduledFuture 的单元素数组（因为 lambda 内需要在赋值后引用自身）
     */
    private void cancel(ScheduledFuture<?>[] holder) {
        ScheduledFuture<?> future = holder[0];
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * 空监听器：模拟客户端不关心回包内容。
     *
     * <p>{@link WebSocket.Listener} 的所有回调都有默认实现，这里只需一个实例即可。
     */
    private static final class NoopListener implements WebSocket.Listener {
    }
}
