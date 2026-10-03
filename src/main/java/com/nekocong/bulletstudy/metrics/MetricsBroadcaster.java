package com.nekocong.bulletstudy.metrics;

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
 * 指标推送器：每秒把所有指标作为一帧推送给每个在线会话。
 *
 * <p><b>端到端链路中的位置：</b>
 * <pre>
 *   MetricsRegistry.snapshot() ──每秒──▶ 本类 ──▶ 每个在线会话发送 {"type":"metrics", ...}
 * </pre>
 *
 * <p><b>为什么指标帧不走弹幕合批队列：</b>合批队列的帧结构固定为
 * {@code {"type":"danmaku","items":[...]}}，且语义是“多条弹幕合并”。指标是独立的、定频的控制帧，
 * 若混进队列会破坏帧结构、并被当作弹幕参与 drop-oldest 统计。因此它走<b>自己的直发路径</b>。
 *
 * <p><b>为什么只在本地发送、不进 Redis：</b>每个实例的指标是“本实例视角”的（本机连接数、
 * 本机计数器），不该被跨实例广播；前端连到哪个实例就读哪个实例的指标。
 *
 * <p><b>失败隔离：</b>某个会话发送失败（已关闭、装饰器超限）只记录，不影响其余会话的推送。
 */
@Component
public class MetricsBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(MetricsBroadcaster.class);

    private final SessionRegistry sessionRegistry;

    private final MetricsRegistry metricsRegistry;

    private final ObjectMapper objectMapper;

    /**
     * 构造器注入。
     *
     * @param sessionRegistry 在线会话来源
     * @param metricsRegistry 指标来源
     * @param objectMapper    Jackson 序列化器
     */
    public MetricsBroadcaster(SessionRegistry sessionRegistry,
                              MetricsRegistry metricsRegistry,
                              ObjectMapper objectMapper) {
        this.sessionRegistry = sessionRegistry;
        this.metricsRegistry = metricsRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * 每秒推送一次指标帧。
     *
     * <p>{@code fixedRate=1000}：指标是定频观测，用固定频率而非“上次结束后间隔”。
     * 推送本身很轻（一次快照 + 序列化 + N 次发送），不会造成任务堆积。
     */
    @Scheduled(fixedRate = 1000L)
    public void pushMetrics() {
        String payload;
        try {
            payload = this.objectMapper.writeValueAsString(
                    MetricsFrame.of(this.metricsRegistry.snapshot()));
        } catch (JsonProcessingException e) {
            log.warn("[metrics] failed to serialize metrics frame: {}", e.getMessage());
            return;
        }
        for (WebSocketSession session : this.sessionRegistry.allSessions()) {
            try {
                session.sendMessage(new TextMessage(payload));
            } catch (Exception e) {
                // 单个会话失败不影响其它会话；典型原因是该会话已被装饰器判为不可靠而关闭。
                log.debug("[metrics] push to session {} failed: {}", session.getId(), e.getMessage());
            }
        }
    }

    /**
     * 指标帧载体：{@code {"type":"metrics", ...9 字段...}}（字段扁平，不嵌套）。
     *
     * @param type 恒为 {@code "metrics"}
     */
    public record MetricsFrame(
            String type,
            long connections,
            long totalReceived,
            long totalPublished,
            long totalBroadcast,
            long publishRate,
            long broadcastRate,
            long avgLatencyMs,
            long p99LatencyMs,
            long dropped) {

        /**
         * 由快照展开为扁平帧（字段顺序与 wire contract 一致）。
         *
         * @param snapshot 指标快照
         * @return 带 type 的指标帧
         */
        public static MetricsFrame of(MetricsSnapshot snapshot) {
            return new MetricsFrame(
                    "metrics",
                    snapshot.connections(),
                    snapshot.totalReceived(),
                    snapshot.totalPublished(),
                    snapshot.totalBroadcast(),
                    snapshot.publishRate(),
                    snapshot.broadcastRate(),
                    snapshot.avgLatencyMs(),
                    snapshot.p99LatencyMs(),
                    snapshot.dropped());
        }
    }
}
