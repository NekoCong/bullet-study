package com.nekocong.bulletstudy.realtime;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.bulletstudy.danmaku.BroadcastService;
import com.nekocong.bulletstudy.danmaku.DanmakuMessage;

/**
 * 弹幕 WebSocket 网关（端点 {@code /ws/danmaku}）。
 *
 * <p><b>端到端链路中的位置：</b>
 * <pre>
 *   浏览器 / 模拟器 ──Upgrade /ws/danmaku?roomId=xxx──▶ 本类 afterConnectionEstablished
 *        │  ① 从握手 URI 解析 roomId（缺省 room-1）
 *        │  ② 用 ConcurrentWebSocketSessionDecorator 包装原始会话（串行化发送 + 背压保护）
 *        │  ③ 交给 SessionRegistry.register 登记
 *        ▼
 *   （数据流方向）连接建立后：后续的入站文本 JSON 解析、广播、合批下发由其它组件负责；
 *   连接关闭时：本类 afterConnectionClosed ──▶ SessionRegistry.remove 注销
 * </pre>
 *
 * <p><b>本类负责“连接生命周期 + 入站解析”两件事：</b>解析房间号、装饰会话、登记/注销，
 * 以及把入站 JSON 文本帧解析后交给广播核心（见 {@link #handleTextMessage}）。
 * 真正的“发布/合批/下发”不在这里，而在 {@code BroadcastService} 与 {@code FlushScheduler}，
 * 这样网关只管“谁在线、收到了什么”，业务只管“怎么广播出去”。
 *
 * <p><b>为什么必须用 {@link ConcurrentWebSocketSessionDecorator}（本类最核心的知识点）：</b>
 * <ul>
 *   <li><b>JSR-356 禁止并发发送。</b>同一个 {@code WebSocketSession} 的底层
 *       {@code RemoteEndpoint} 不允许两个线程同时做"部分写"。本项目的下发至少有两个来源：
 *       合批刷新调度线程、指标推送调度线程；当它们同时向同一会话调用
 *       {@code sendMessage} 时，Tomcat/JSR-356 会抛
 *       {@code IllegalStateException: The remote endpoint was in state [TEXT_PARTIAL_WRITING]}
 *       （即"上一次写还没结束，又来了一个写"），甚至可能把两帧的字节交错写坏。
 *       装饰器在内部给每个会话加锁 / 排队，保证"同一会话任意时刻只有一次发送在执行"。</li>
 *   <li><b>背压保护。</b>装饰器有两个上限（均来自 application.yml）：
 *       <ul>
 *         <li>{@code sendTimeLimit}（danmaku.send-time-limit-ms，默认 5000ms）：
 *             从一次发送开始计时，若超时仍未完成，说明该客户端读得太慢；</li>
 *         <li>{@code bufferSizeLimit}（danmaku.buffer-size-limit-bytes，默认 1MB）：
 *             当上一帧还没发完、新帧只能排队时，限制排队字节总量。</li>
 *       </ul>
 *       任一超限，装饰器会主动关闭该会话（{@link CloseStatus#SESSION_NOT_RELIABLE}）。
 *       用"牺牲一个慢客户端"换取"服务端内存不被拖垮、广播线程不被阻塞"。</li>
 * </ul>
 * 注意：装饰器的"超限关闭会话"与业务层合批队列的"drop-oldest 丢消息"是<b>两层独立防线</b>：
 * 前者保护的是网络发送层（会话级），后者保护的是业务队列（消息级），不要混为一谈。
 *
 * @see SessionRegistry
 * @see com.nekocong.bulletstudy.config.WebSocketConfig
 */
@Component
public class DanmakuWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DanmakuWebSocketHandler.class);

    /**
     * 未在 URI query 里指定 roomId 时的默认房间。
     * 与订阅端（RedisMessageListenerContainer 启动时只注册 room-1）保持一致，
     * 保证"不带参数直接连"也能收到广播。
     */
    public static final String DEFAULT_ROOM_ID = "room-1";

    /** 握手 URI 中携带房间号的 query 参数名，见 wire contract：房间随连接走，不放在消息体里。 */
    private static final String QUERY_PARAM_ROOM_ID = "roomId";

    private final SessionRegistry sessionRegistry;

    /** 广播核心：入站弹幕解析后交给它做校验、统计与 Redis 发布。 */
    private final BroadcastService broadcastService;

    /** Jackson 解析器：把入站文本帧解析为 JSON 树后按类型分派。 */
    private final ObjectMapper objectMapper;

    /** 单次发送允许占用的最长时间（毫秒），透传给装饰器。 */
    private final int sendTimeLimitMs;

    /** 单会话允许积压的发送缓冲上限（字节），透传给装饰器。 */
    private final int bufferSizeLimitBytes;

    /**
     * 构造器注入（学习项目统一使用构造器注入，而不是字段 {@code @Autowired}）：
     * 依赖在对象创建时就确定，便于阅读与测试；配置值通过 {@code @Value} 从 application.yml 读取。
     *
     * @param sessionRegistry       房间会话注册表
     * @param broadcastService      广播核心
     * @param objectMapper          统一 Jackson 解析器
     * @param sendTimeLimitMs       danmaku.send-time-limit-ms
     * @param bufferSizeLimitBytes  danmaku.buffer-size-limit-bytes
     */
    public DanmakuWebSocketHandler(SessionRegistry sessionRegistry,
                                   BroadcastService broadcastService,
                                   ObjectMapper objectMapper,
                                   @Value("${danmaku.send-time-limit-ms}") int sendTimeLimitMs,
                                   @Value("${danmaku.buffer-size-limit-bytes}") int bufferSizeLimitBytes) {
        this.sessionRegistry = sessionRegistry;
        this.broadcastService = broadcastService;
        this.objectMapper = objectMapper;
        this.sendTimeLimitMs = sendTimeLimitMs;
        this.bufferSizeLimitBytes = bufferSizeLimitBytes;
    }

    /**
     * 连接建立回调：解析房间号 → 装饰会话 → 登记进注册表。
     *
     * <p>这是一个新的长连接诞生的入口，也是"装饰器防线"的安装点：注册表里只保存装饰后的对象，
     * 后续所有下发都必须经过它，才会获得"发送串行化 + 时间/缓冲上限"的保护。
     *
     * @param session 框架刚建立好的原始会话（未装饰）
     * @throws Exception 由父类约定；本实现不主动抛出
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String roomId = resolveRoomId(session);
        // 关键：包装（而不是直接用原始 session）。参数含义见类注释：时间上限 + 缓冲字节上限。
        WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(
                session, this.sendTimeLimitMs, this.bufferSizeLimitBytes);
        this.sessionRegistry.register(roomId, decorated);
        // 这行注册日志是"网关可用"的验收证据：能看到房间号、会话 id、房间内与全局在线数。
        log.info("[ws] connection registered: roomId={}, sessionId={}, roomConnections={}, totalConnections={}",
                roomId, decorated.getId(),
                this.sessionRegistry.sessions(roomId).size(),
                this.sessionRegistry.connectionCount());
    }

    /**
     * 连接关闭回调：从注册表注销该会话。
     *
     * <p>注意参数是<b>原始</b> {@code WebSocketSession}（不是注册表里的装饰对象）；
     * {@link SessionRegistry#remove(WebSocketSession)} 内部按"同一实例或相同 sessionId"匹配，
     * 并通过 session 属性里的房间号 O(1) 定位房间。任何关闭原因（客户端主动断开、
     * 装饰器因超限关闭、Tomcat 回收）都会走到这里，保证注册表不泄漏"幽灵连接"。
     *
     * @param session 已关闭的原始会话
     * @param status  关闭状态码与原因
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String roomId = this.sessionRegistry.remove(session);
        // 连接没了，它在广播核心里的合批队列也要一并清理，避免“幽灵队列”随连接反复建立而泄漏。
        this.broadcastService.removeBuffer(session.getId());
        log.info("[ws] connection closed: roomId={}, sessionId={}, status={}, totalConnections={}",
                roomId, session.getId(), status, this.sessionRegistry.connectionCount());
    }

    /**
     * 入站文本帧处理：只认 {@code {"type":"danmaku","text":"..."}}，其余一律忽略。
     *
     * <p><b>为什么房间号不取消息体：</b>房间由握手 URI 决定（wire contract），消息体里即使带了
     * roomId 也不可信；这里用 {@link SessionRegistry#roomOf(WebSocketSession)} 反查注册时写入的房间，
     * 保证“一条连接的所有消息只属于一个房间”，无需逐条校验归属。
     *
     * <p><b>为什么可选读取 ts：</b>契约要求的最小客户端帧只有 type 与 text；若客户端愿意带上
     * {@code ts}（客户端毫秒时间戳），服务端就能统计端到端延迟。缺省时用接收时刻兜底，延迟记为 0。
     *
     * <p><b>容错原则：</b>未知 type、空白文本、非法 JSON 都<b>静默忽略</b>（仅 debug 日志），
     * 绝不抛异常——一个坏客户端不应该能打断它自己或他人的连接。
     *
     * @param session 发送方会话（原始对象；房间号可从共享 attributes 反查）
     * @param message 文本帧
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String roomId = this.sessionRegistry.roomOf(session);
        if (roomId == null) {
            roomId = DEFAULT_ROOM_ID;
        }
        try {
            JsonNode root = this.objectMapper.readTree(message.getPayload());
            String type = root.path("type").asText("");
            if (!DanmakuMessage.TYPE.equals(type)) {
                // 未知类型（例如未来扩展的控制帧）直接忽略，保持前向兼容。
                log.debug("[ws] ignore inbound frame type={} roomId={}", type, roomId);
                return;
            }
            String text = root.path("text").asText(null);
            if (text == null || text.isBlank()) {
                return;
            }
            long ts = root.path("ts").asLong(0L);
            this.broadcastService.ingest(roomId, text, ts);
        } catch (JsonProcessingException e) {
            // 非法 JSON：忽略并记录，不向客户端抛错。
            log.debug("[ws] ignore malformed inbound frame roomId={}: {}", roomId, e.getOriginalMessage());
        }
    }

    /**
     * 从握手 URI 的 query 中解析房间号。
     *
     * <p>wire contract：房间由连接 URI 决定（例如 {@code /ws/danmaku?roomId=room-1}），
     * 消息体里不带房间号。这样同一连接的所有消息天然归属同一房间，服务端无需为每条消息做归属校验。
     *
     * @param session 已建立的会话（原始/装饰对象均可，两者 getUri() 行为一致）
     * @return 解析出的房间号；URI 缺失、query 缺失或值为空白时回退为 {@link #DEFAULT_ROOM_ID}
     */
    private String resolveRoomId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            // 理论上服务端会话一定有 URI；兜底避免 NPE，保证连接仍可用。
            return DEFAULT_ROOM_ID;
        }
        // 这里会做 URL 解码：roomId 里出现中文/空格等编码字符时也能正确还原。
        String roomId = UriComponentsBuilder.fromUri(uri).build()
                .getQueryParams().getFirst(QUERY_PARAM_ROOM_ID);
        if (roomId == null || roomId.isBlank()) {
            return DEFAULT_ROOM_ID;
        }
        return roomId.trim();
    }
}
