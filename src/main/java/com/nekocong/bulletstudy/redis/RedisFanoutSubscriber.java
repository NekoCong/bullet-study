package com.nekocong.bulletstudy.redis;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.bulletstudy.danmaku.BroadcastService;
import com.nekocong.bulletstudy.danmaku.DanmakuMessage;

/**
 * Redis 订阅端：监听房间频道，把收到的弹幕转交给本地广播。
 *
 * <p><b>端到端链路中的位置：</b>这是“Redis 回环”的落点，也是本进程把消息发给本地连接的地方：
 * <pre>
 *   Redis 频道 danmaku:room:room-1
 *        │  本类.onMessage（由 RedisMessageListenerContainer 的监听线程回调）
 *        ▼
 *   BroadcastService.broadcastLocal(roomId, message)
 *        │
 *        ▼
 *   房间内每个会话的合批队列 ──▶ FlushScheduler 下发
 * </pre>
 *
 * <p><b>为什么每条消息都走 Redis 再回来：</b>见 {@link BroadcastService} 的类注释。
 * 对本类而言，无论消息来自本机还是集群中其它实例，处理方式完全一样——这正是可扩展性的来源。
 *
 * <p><b>异常隔离：</b>解析失败或广播异常都必须在此被捕获并记录。若让异常逃逸到
 * {@code RedisMessageListenerContainer}，会污染监听线程甚至影响后续消息消费；
 * 容器级 {@code ErrorHandler}（见 {@link RedisConfig}）是最后一道兜底，本类是第一道。
 */
@Component
public class RedisFanoutSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisFanoutSubscriber.class);

    private final BroadcastService broadcastService;

    private final ObjectMapper objectMapper;

    /**
     * 构造器注入。
     *
     * @param broadcastService 本地广播服务
     * @param objectMapper     统一 Jackson 反序列化器
     */
    public RedisFanoutSubscriber(BroadcastService broadcastService, ObjectMapper objectMapper) {
        this.broadcastService = broadcastService;
        this.objectMapper = objectMapper;
    }

    /**
     * 频道消息回调：反序列化 payload，按消息自带的 roomId 做本地广播。
     *
     * <p>这里从消息体读 roomId（而不是从频道名解析），因为消息体是权威数据源；
     * 频道名仅用于“谁订阅”，二者在正常流程下一致。
     *
     * @param message Redis 原始消息（body 为 UTF-8 JSON）
     * @param pattern 订阅模式；精确频道订阅时为 null，本实现不使用
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            DanmakuMessage danmaku = this.objectMapper.readValue(payload, DanmakuMessage.class);
            this.broadcastService.broadcastLocal(danmaku.roomId(), danmaku);
        } catch (Exception e) {
            // 单条坏消息不应打断订阅循环，记录后丢弃。
            log.warn("[redis] failed to handle channel message: {}", e.getMessage());
        }
    }
}
