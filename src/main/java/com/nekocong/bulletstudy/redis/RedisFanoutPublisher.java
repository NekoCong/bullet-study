package com.nekocong.bulletstudy.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.bulletstudy.danmaku.DanmakuMessage;
import com.nekocong.bulletstudy.metrics.MetricsRegistry;

/**
 * Redis 发布器：把弹幕序列化后投递到 {@code danmaku:room:<roomId>} 频道。
 *
 * <p><b>端到端链路中的位置：</b>
 * <pre>
 *   BroadcastService.ingest ──▶ 本类.publish ──▶ Redis 频道 danmaku:room:room-1
 *                                                      │
 *                                                      ▼
 *                                   RedisFanoutSubscriber.onMessage（同进程或其它实例）
 * </pre>
 *
 * <p><b>为什么用 Redis Pub/Sub：</b>它是“发布即忘（at-most-once）”的扇出原语：实现简单、延迟低，
 * 天然支持多实例订阅同一频道。代价是不持久化、离线客户端收不到——但本项目不做历史/回放，
 * 这个取舍正好匹配“实时弹幕”的语义。
 *
 * <p><b>频道命名：</b>每个房间一个频道 {@code danmaku:room:<roomId>}，订阅端按房间订阅，
 * 避免所有房间的消息互相打扰（虽然本 MVP 只订阅默认房间）。
 */
@Component
public class RedisFanoutPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisFanoutPublisher.class);

    /** 房间频道前缀；拼接 roomId 得到完整频道名。 */
    public static final String CHANNEL_PREFIX = "danmaku:room:";

    private final RedisTemplate<String, String> redisTemplate;

    private final RedisConnectionFactory connectionFactory;

    private final ObjectMapper objectMapper;

    private final MetricsRegistry metricsRegistry;

    /**
     * 构造器注入。
     *
     * @param redisTemplate    使用 String 序列化器的模板（见 {@link RedisConfig}）
     * @param connectionFactory 用于健康检查时直连 ping
     * @param objectMapper      统一 Jackson 序列化器
     * @param metricsRegistry   指标注册表
     */
    public RedisFanoutPublisher(RedisTemplate<String, String> redisTemplate,
                                RedisConnectionFactory connectionFactory,
                                ObjectMapper objectMapper,
                                MetricsRegistry metricsRegistry) {
        this.redisTemplate = redisTemplate;
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
        this.metricsRegistry = metricsRegistry;
    }

    /**
     * 计算房间对应的 Redis 频道名。
     *
     * @param roomId 房间号
     * @return {@code danmaku:room:<roomId>}
     */
    public static String channelFor(String roomId) {
        return CHANNEL_PREFIX + roomId;
    }

    /**
     * 发布一条弹幕到房间频道。
     *
     * <p>序列化失败视为编程错误：记录告警并返回，不抛出，避免单条坏数据打断广播链路。
     *
     * @param roomId  房间号
     * @param message 弹幕消息
     */
    public void publish(String roomId, DanmakuMessage message) {
        String payload;
        try {
            payload = this.objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.warn("[redis] failed to serialize danmaku for room {}: {}", roomId, e.getMessage());
            return;
        }
        this.redisTemplate.convertAndSend(channelFor(roomId), payload);
        this.metricsRegistry.recordPublished();
    }

    /**
     * Redis 健康探测（供 {@code /api/health} 与模拟器前置检查使用）。
     *
     * <p>用 {@link RedisTemplate#execute(RedisCallback)} 发起一次 PING：任何连接/超时异常都视为
     * “不健康”并返回 false，绝不向上抛出——健康检查必须永远能给出 200/503，而不是 500。
     *
     * @return Redis 返回 PONG 时为 true，否则 false
     */
    public boolean pingRedis() {
        try {
            String pong = this.redisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
            return "PONG".equalsIgnoreCase(pong);
        } catch (RuntimeException e) {
            log.debug("[redis] ping failed: {}", e.getMessage());
            return false;
        }
    }
}
