package com.nekocong.bulletstudy.danmaku;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.bulletstudy.realtime.SessionRegistry;

/**
 * 合批刷新调度器：按固定间隔把各会话的弹幕队列合并成“一帧多弹”下发。
 *
 * <p><b>端到端链路中的位置：</b>本类是“消息 → 网络帧”的最后一步：
 * <pre>
 *   BroadcastService 的各会话 ArrayDeque
 *        │  每 50ms（danmaku.flush-interval-ms）
 *        ▼
 *   对每个在线会话 drain() ──▶ 合并为 {"type":"danmaku","items":[...]} ──▶ session.sendMessage
 * </pre>
 *
 * <p><b>为什么是 fixedDelay 而不是 fixedRate：</b>{@code fixedDelay} 表示“上一次任务执行结束后再等
 * 指定间隔”。若某次刷新因为大批量序列化/发送耗时而变慢，fixedDelay 会自动让出 CPU，不会让任务
 * 堆积成风暴；fixedRate 则可能在一个执行还没结束时又触发下一次，导致雪崩。
 * 对兜底性质的合批刷新，稳定优先于严格节拍。
 *
 * <p><b>依赖 {@code @EnableScheduling}：</b>本类靠 {@link Scheduled} 生效，必须由启动类开启调度支持。
 *
 * <p><b>发送线程与装饰器：</b>本调度线程与 {@code MetricsBroadcaster} 的调度线程会并发向同一会话
 * 发送。注册表里存的是 {@code ConcurrentWebSocketSessionDecorator}，它保证同一会话的发送串行化，
 * 因此这里可以放心直接 {@code sendMessage}；若装饰器判定该会话发送超时/积压超限，会抛出异常并
 * 关闭该会话，本类捕获后仅记录，绝不让单个慢客户端中断整轮刷新（其余会话照常收到）。
 */
@Component
public class FlushScheduler {

    private static final Logger log = LoggerFactory.getLogger(FlushScheduler.class);

    private final SessionRegistry sessionRegistry;

    private final BroadcastService broadcastService;

    private final ObjectMapper objectMapper;

    /**
     * 构造器注入。
     *
     * @param sessionRegistry  用于拿到当前所有在线会话
     * @param broadcastService 用于 drain 各会话队列
     * @param objectMapper     Spring 容器统一的 Jackson 序列化器
     */
    public FlushScheduler(SessionRegistry sessionRegistry,
                          BroadcastService broadcastService,
                          ObjectMapper objectMapper) {
        this.sessionRegistry = sessionRegistry;
        this.broadcastService = broadcastService;
        this.objectMapper = objectMapper;
    }

    /**
     * 定时刷新：把所有在线会话各自积压的弹幕合并成一帧发出。
     *
     * <p>帧结构由 wire contract 固定为 {@code {"type":"danmaku","items":[ ... ]}}；
     * 即使只有一条消息也用 items 数组包裹，保证前端解析逻辑唯一。
     */
    @Scheduled(fixedDelayString = "${danmaku.flush-interval-ms}")
    public void flush() {
        for (WebSocketSession session : this.sessionRegistry.allSessions()) {
            List<DanmakuMessage> batch = this.broadcastService.drain(session.getId());
            if (batch.isEmpty()) {
                continue;
            }
            try {
                String payload = this.objectMapper.writeValueAsString(new DanmakuFrame(DanmakuMessage.TYPE, batch));
                session.sendMessage(new TextMessage(payload));
            } catch (JsonProcessingException e) {
                // 序列化失败属于编程/数据错误，记录后继续处理其它会话。
                log.warn("[flush] failed to serialize danmaku frame: {}", e.getMessage());
            } catch (Exception e) {
                // 发送失败（会话已关闭、装饰器超限关闭等）：只记录，不影响其它会话。
                log.debug("[flush] send to session {} failed: {}", session.getId(), e.getMessage());
            }
        }
    }

    /**
     * 下发帧载体：{@code {"type":"danmaku","items":[...]}}。
     *
     * @param type  恒为 {@code "danmaku"}
     * @param items 本批弹幕
     */
    public record DanmakuFrame(String type, List<DanmakuMessage> items) {
    }
}
