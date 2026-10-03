package com.nekocong.bulletstudy.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Redis 装配：字符串模板 + pub/sub 监听容器 + 订阅注册。
 *
 * <p><b>端到端链路中的位置：</b>本类是 Redis 侧的“接线处”：
 * <pre>
 *   RedisConnectionFactory（由 spring.data.redis.* 自动装配，读 application.yml）
 *        ├── RedisTemplate&lt;String,String&gt;  ──▶ RedisFanoutPublisher
 *        └── RedisMessageListenerContainer
 *                 └── addMessageListener(subscriber, danmaku:room:room-1) ──▶ RedisFanoutSubscriber
 * </pre>
 *
 * <p><b>为什么用 String 序列化器：</b>弹幕 payload 是 JSON 文本，频道名是字符串。
 * 默认的 JDK 序列化会产生带类信息的二进制，既不可读也不跨语言；String 序列化让 Redis 里
 * 存的就是明文 JSON，方便 {@code redis-cli MONITOR} 观察与排障，学习价值更高。
 *
 * <p><b>为什么给监听容器配独立线程池：</b>{@code RedisMessageListenerContainer} 需要一个
 * {@code TaskExecutor} 来运行监听循环。如果复用应用主线程池，长驻阻塞的订阅循环会占用业务线程。
 * 这里用专用的 {@link ThreadPoolTaskExecutor}，与 Web/调度线程隔离，互不影响。
 *
 * <p><b>本 MVP 只订阅默认房间：</b>启动时固定注册 {@code danmaku:room:room-1} 的话题。
 * 动态按房间订阅属于多房间版本的范围，不在本 MVP 内（wire contract 与模拟器都以 room-1 为准）。
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    /** 本 MVP 固定订阅的默认房间频道。 */
    public static final String DEFAULT_ROOM_CHANNEL = "danmaku:room:room-1";

    /** 启动时 Redis 不可用的重试间隔（毫秒）。 */
    private static final long RECOVERY_INTERVAL_MS = 5000L;

    /**
     * 字符串键值序列化的 RedisTemplate。
     *
     * <p>Bean 名称 {@code redisTemplate} 会覆盖 Spring Boot 自动配置的 {@code RedisTemplate<Object,Object>}
     * （自动配置带 {@code @ConditionalOnMissingBean(name="redisTemplate")}）。但自动配置仍会提供
     * {@code stringRedisTemplate}，它也是 {@code RedisTemplate<String,String>}，导致按类型注入有两个候选。
     * 因此本 Bean 标注 {@link Primary}，让“按类型注入 RedisTemplate&lt;String,String&gt;”时唯一指向它。
     *
     * @param connectionFactory 由 spring.data.redis.* 自动装配的连接工厂
     * @return String 序列化的模板
     */
    @Bean
    @Primary
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(RedisSerializer.string());
        template.setValueSerializer(RedisSerializer.string());
        template.setHashKeySerializer(RedisSerializer.string());
        template.setHashValueSerializer(RedisSerializer.string());
        // 手动构造的模板需要显式初始化，否则内部序列化器不会就绪。
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 订阅监听容器：绑定连接工厂与专用线程池。
     *
     * <p><b>为什么这里“先不注册监听器”（关键健壮性处理）：</b>
     * 容器默认在上下文刷新时自动 {@code start()}，而 {@code start()} 内部的 {@code lazyListen()}
     * 只有在“已注册监听器”时才去连 Redis 订阅；一旦连不上，Spring Data Redis 3.5 会把异常抛出，
     * 导致<b>整个应用启动失败</b>。本项目要求“Redis 挂了应用仍要活着，由 /api/health 报 503”，
     * 所以此处只装配不订阅；真正的 {@code addMessageListener} 由 {@link #redisListenerStarter}
     * 在探测到 Redis 可达后再触发（添加监听器会内部调用 {@code lazyListen()} 完成订阅）。
     *
     * <p>ErrorHandler 是容器级兜底：监听线程抛出的异常若不处理会被容器吞掉或导致循环异常。
     * 这里统一记录为错误日志，保证“异常可见但不致命”。
     *
     * @param connectionFactory 连接工厂
     * @param redisListenerExecutor 专用监听线程池
     * @return 已装配但尚未注册监听器的容器
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            ThreadPoolTaskExecutor redisListenerExecutor) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(redisListenerExecutor);
        container.setErrorHandler(error -> log.error("[redis] listener error: {}", error.getMessage(), error));
        // 订阅建立后若连接断开，容器按此间隔自动重连。
        container.setRecoveryInterval(RECOVERY_INTERVAL_MS);
        return container;
    }

    /**
     * 订阅启动器：等待 Redis 可达后再注册监听器，从而建立订阅。
     *
     * <p><b>为什么不在启动线程里同步等待：</b>如果在这里阻塞，应用会迟迟不进入“已启动”状态，
     * 健康检查端口也无法及时可用。因此用一条守护线程后台轮询：Redis 一可达就注册监听器触发订阅，
     * 之后由容器的自动重连机制维持；Redis 始终不可用时应用照常运行，只是 /api/health 返回 503。
     *
     * @param container     监听容器（启动时无监听器，不会连接 Redis）
     * @param subscriber    业务监听器
     * @param redisTemplate 用于 PING 探测的字符串模板
     * @return 应用就绪后触发的启动器
     */
    @Bean
    public ApplicationRunner redisListenerStarter(RedisMessageListenerContainer container,
                                                 RedisFanoutSubscriber subscriber,
                                                 RedisTemplate<String, String> redisTemplate) {
        return args -> {
            Thread starter = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        String pong = redisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
                        if ("PONG".equalsIgnoreCase(pong)) {
                            // 注册监听器即触发 lazyListen → 建立订阅；万一 Redis 恰好又断开则重试。
                            container.addMessageListener(subscriber, new ChannelTopic(DEFAULT_ROOM_CHANNEL));
                            log.info("[redis] listener container subscribed to {}", DEFAULT_ROOM_CHANNEL);
                            return;
                        }
                    } catch (RuntimeException e) {
                        log.warn("[redis] waiting for Redis before subscribing: {}", e.getMessage());
                    }
                    try {
                        Thread.sleep(RECOVERY_INTERVAL_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "redis-listener-starter");
            starter.setDaemon(true);
            starter.start();
        };
    }

    /**
     * 订阅监听的专用线程池。
     *
     * <p>核心 2 线程足够：单个订阅循环 + 少量并发回调；线程名带前缀便于在日志/线程 dump 中识别。
     *
     * @return 隔离的监听线程池
     */
    @Bean
    public ThreadPoolTaskExecutor redisListenerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setThreadNamePrefix("redis-listener-");
        executor.initialize();
        return executor;
    }
}
