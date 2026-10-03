package com.nekocong.bulletstudy.danmaku;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.nekocong.bulletstudy.metrics.MetricsRegistry;
import com.nekocong.bulletstudy.redis.RedisFanoutPublisher;
import com.nekocong.bulletstudy.realtime.SessionRegistry;

/**
 * 弹幕广播核心：把“接收到的弹幕”变成“待下发给各会话的队列”。
 *
 * <p><b>端到端链路中的位置：</b>本类是数据流的枢纽，串起“接收 → 发布 → 订阅 → 本地入队”：
 * <pre>
 *   DanmakuWebSocketHandler.handleTextMessage
 *        │  ingest(roomId, text, ts)
 *        ▼
 *   校验/裁剪文本 ── recordReceived ──▶ RedisFanoutPublisher.publish(roomId, msg)
 *        │                                        │（Redis pub/sub 回环到本进程订阅端）
 *        │                                        ▼
 *        │                            RedisFanoutSubscriber.onMessage
 *        │                                        │  broadcastLocal(roomId, msg)
 *        ▼                                        ▼
 *   ┌──────────────── 本类 ────────────────┐  对房间内每个在线会话：
 *   │ 每会话一个 ArrayDeque（合批队列，cap=max-buffer-per-session）│
 *   │ 超限 drop-oldest + recordDropped      │  入队成功 recordBroadcast
 *   └───────────────────────────────────────┘
 *        │
 *        ▼
 *   FlushScheduler 每 50ms drain 一次，合并成一帧下发
 * </pre>
 *
 * <p><b>为什么走 Redis 再回来（而不是直接广播）：</b>这是为了对齐“多实例生产形态”。
 * 单实例时它多了一跳看起来多余，但正因如此：任意实例 publish 的消息，所有实例的订阅端都会收到，
 * 天然具备横向扩展时的跨实例扇出能力。学习项目的价值正在于这个“看起来很傻但很关键”的结构。
 *
 * <p><b>为什么每个会话独立队列 + 定时合批（而不是来一条发一条）：</b>
 * 广播洪峰下若每条消息都立即 {@code sendMessage}，会产生海量小帧、放大系统调用与网络包开销。
 * 合批把 50ms 内的多条消息压成一帧，代价是最多 50ms 延迟——用一点点实时性换取吞吐。
 *
 * <p><b>两层防线（务必区分）：</b>
 * <ul>
 *   <li><b>本类的合批队列</b>：消息级，超限丢“最旧”的消息（drop-oldest），只丢这一条；</li>
 *   <li><b>ConcurrentWebSocketSessionDecorator</b>：会话级，发送超时/缓冲超限直接<b>关闭会话</b>。</li>
 * </ul>
 * 二者独立：前者保证内存有界，后者保证一个读得慢的客户端不会拖垮发送线程。
 */
@Component
public class BroadcastService {

    private static final Logger log = LoggerFactory.getLogger(BroadcastService.class);

    /** 单条弹幕文本的最大长度；超出直接截断，防止异常大帧打爆带宽/内存。 */
    private static final int MAX_TEXT_LENGTH = 200;

    private final SessionRegistry sessionRegistry;

    private final RedisFanoutPublisher fanoutPublisher;

    private final MetricsRegistry metricsRegistry;

    /**
     * 单会话合批队列上限（条）。映射 {@code danmaku.max-buffer-per-session}。
     * 超过后丢弃最旧消息，保证慢客户端不会让队列无限增长。
     */
    private final int maxBufferPerSession;

    /**
     * 会话 id → 该会话的合批队列。
     *
     * <p>用 {@link ConcurrentHashMap} 管理映射本身；队列内部的所有读写（offer/poll/size）
     * 统一在队列对象上加 {@code synchronized}，因为 {@link ArrayDeque} 非线程安全，
     * 而入队（Redis 订阅线程）与出队（FlushScheduler 调度线程）是并发的。
     */
    private final Map<String, ArrayDeque<DanmakuMessage>> buffers = new ConcurrentHashMap<>();

    /**
     * 构造器注入。
     *
     * @param sessionRegistry      房间会话注册表（决定“发给谁”）
     * @param fanoutPublisher      Redis 发布器（决定“怎么广播出去”）
     * @param metricsRegistry      指标注册表
     * @param maxBufferPerSession  单会话队列上限，来自配置
     */
    public BroadcastService(SessionRegistry sessionRegistry,
                            RedisFanoutPublisher fanoutPublisher,
                            MetricsRegistry metricsRegistry,
                            @Value("${danmaku.max-buffer-per-session}") int maxBufferPerSession) {
        this.sessionRegistry = sessionRegistry;
        this.fanoutPublisher = fanoutPublisher;
        this.metricsRegistry = metricsRegistry;
        this.maxBufferPerSession = maxBufferPerSession;
    }

    /**
     * 接收一条弹幕：校验 → 裁剪 → 统计 → 发布到 Redis。
     *
     * <p>注意：这里只做“发布”，不做本地广播。本地广播由 Redis 订阅端回调
     * {@link #broadcastLocal(String, DanmakuMessage)} 完成，从而与其他实例行为一致。
     *
     * @param roomId 房间号（来自连接的 URI）
     * @param text   原始文本；null/空白会被静默忽略
     * @param ts     客户端时间戳（毫秒）；&lt;=0 时用服务端接收时刻兜底
     */
    public void ingest(String roomId, String text, long ts) {
        String normalized = normalizeText(text);
        if (normalized == null) {
            return;
        }
        long serverTs = System.currentTimeMillis();
        long clientTs = ts > 0 ? ts : serverTs;
        DanmakuMessage message = DanmakuMessage.of(roomId, normalized, clientTs, serverTs);
        // 累计“接收”与延迟样本（延迟样本在 MetricsRegistry 内部已做时钟偏移防护）。
        this.metricsRegistry.recordReceived();
        this.metricsRegistry.recordLatency(clientTs, serverTs);
        // 发布到 Redis；发布成功由 publisher 累计 totalPublished。
        this.fanoutPublisher.publish(roomId, message);
    }

    /**
     * 把一条弹幕入队到某房间所有在线会话（由 Redis 订阅端回调）。
     *
     * <p>“广播”在这里的实现是“每收件人入队一次”，而非直接发送——真正发送在
     * {@code FlushScheduler}。这样调度与来源解耦：无论消息来自本机还是其它实例，路径一致。
     *
     * @param roomId  房间号
     * @param message 弹幕消息
     */
    public void broadcastLocal(String roomId, DanmakuMessage message) {
        for (var session : this.sessionRegistry.sessions(roomId)) {
            enqueue(session.getId(), message);
            // 每条“入队”都算一次成功广播（按收件人计）。
            this.metricsRegistry.recordBroadcast();
        }
    }

    /**
     * 取出并清空某会话的合批队列（FlushScheduler 每 50ms 调用一次）。
     *
     * @param sessionId 会话 id
     * @return 本次可发送的消息列表；无消息时返回空列表（绝不返回 null）
     */
    public List<DanmakuMessage> drain(String sessionId) {
        ArrayDeque<DanmakuMessage> queue = this.buffers.get(sessionId);
        if (queue == null) {
            return List.of();
        }
        synchronized (queue) {
            if (queue.isEmpty()) {
                return List.of();
            }
            List<DanmakuMessage> batch = new ArrayList<>(queue);
            queue.clear();
            return batch;
        }
    }

    /**
     * 会话关闭时清理其合批队列，避免“幽灵队列”随连接反复建立/关闭而泄漏。
     *
     * @param sessionId 会话 id
     */
    public void removeBuffer(String sessionId) {
        this.buffers.remove(sessionId);
    }

    /**
     * 入队实现：超限丢弃最旧（drop-oldest）。
     *
     * <p>为什么丢最旧而不是最新：弹幕是实时性内容，保留最新消息对观众更有价值；
     * 丢最旧的语义也与“落后就追赶”的直觉一致。丢弃会计入 {@code dropped} 指标。
     *
     * @param sessionId 目标会话 id
     * @param message   待入队消息
     */
    private void enqueue(String sessionId, DanmakuMessage message) {
        ArrayDeque<DanmakuMessage> queue =
                this.buffers.computeIfAbsent(sessionId, key -> new ArrayDeque<>());
        synchronized (queue) {
            if (queue.size() >= this.maxBufferPerSession) {
                queue.pollFirst();
                this.metricsRegistry.recordDropped();
            }
            queue.addLast(message);
        }
    }

    /**
     * 文本校验与裁剪。
     *
     * @param text 原始文本
     * @return 去除首尾空白并截断到 {@value #MAX_TEXT_LENGTH} 字符的文本；空白输入返回 null
     */
    private String normalizeText(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_TEXT_LENGTH) {
            log.debug("[danmaku] text truncated from {} to {} chars", trimmed.length(), MAX_TEXT_LENGTH);
            return trimmed.substring(0, MAX_TEXT_LENGTH);
        }
        return trimmed;
    }
}
