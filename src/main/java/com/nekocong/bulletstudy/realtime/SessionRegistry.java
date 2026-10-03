package com.nekocong.bulletstudy.realtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import com.nekocong.bulletstudy.metrics.MetricsRegistry;

/**
 * 房间 → 在线 WebSocket 会话 的内存注册表。
 *
 * <p><b>端到端链路中的位置：</b>
 * <pre>
 *   浏览器 / 模拟器
 *        │  GET /ws/danmaku?roomId=room-1  (HTTP Upgrade)
 *        ▼
 *   {@link DanmakuWebSocketHandler#afterConnectionEstablished}  ── register() ──▶ 本类
 *        │                                                                        │
 *        │  连接关闭                                                               │  sessions(roomId)
 *        ▼                                                                        ▼
 *   {@link DanmakuWebSocketHandler#afterConnectionClosed}  ── remove() ──▶   FlushScheduler / MetricsBroadcaster
 *                                                                              （对房间内所有会话下发数据帧）
 * </pre>
 *
 * <p><b>为什么要注册表：</b>WebSocket 是长连接，服务端要把"某条弹幕"发给"某个房间里的所有连接"，
 * 就必须随时知道"当前谁在线"。这个映射只存在内存里（学习项目不做持久化），进程重启即清空。
 *
 * <p><b>线程安全（本类最关键的设计点）：</b>连接建立/关闭回调由 Tomcat 的 I/O 线程触发，
 * 而合批刷新、指标推送由调度线程触发，二者会并发读写同一张表。所以：
 * <ul>
 *   <li>外层 Map 用 {@link ConcurrentHashMap}；</li>
 *   <li>每个房间的会话集合用 {@link ConcurrentHashMap#newKeySet()}（并发版 HashSet），
 *       而不是 {@code Collections.synchronizedSet(new HashSet<>())}——后者在"遍历时另一线程增删"
 *       仍需手动加锁，忘记加锁就会抛 ConcurrentModificationException。</li>
 * </ul>
 *
 * <p><b>为什么把房间号写进 session 属性：</b>连接关闭回调只给到 session 对象本身，
 * 不会再传一次房间号。注册时把 roomId 写进 {@link WebSocketSession#getAttributes()}，
 * 注销时就能 O(1) 反查房间，无需遍历所有房间找这个 session。
 * 注意：{@code ConcurrentWebSocketSessionDecorator} 与原始 session 共享同一份 attributes，
 * 所以无论拿到哪个对象都能读到房间号。
 *
 * @see DanmakuWebSocketHandler
 */
@Component
public class SessionRegistry {

    /**
     * 写入 session attributes 的房间号键名。
     * 加 {@code danmaku.} 前缀是为了和框架/其它组件可能使用的属性名隔离，避免键冲突。
     */
    public static final String ATTR_ROOM_ID = "danmaku.roomId";

    /**
     * 房间号 → 该房间在线会话集合。
     * value 永远不为 null；房间没有在线会话时对应的 key 会被移除（见 {@link #remove(WebSocketSession)}）。
     */
    private final Map<String, Set<WebSocketSession>> sessionsByRoom = new ConcurrentHashMap<>();

    /**
     * 指标注册表：连接“登记成功”时 +1、“移除成功”时 -1，保持在线连接 gauge 准确。
     * 只统计真正入表/出表的会话，重复关闭等噪声不会造成计数漂移。
     */
    private final MetricsRegistry metricsRegistry;

    /**
     * 构造器注入。
     *
     * @param metricsRegistry 指标注册表
     */
    public SessionRegistry(MetricsRegistry metricsRegistry) {
        this.metricsRegistry = metricsRegistry;
    }

    /**
     * 把一个已建立的会话登记到指定房间。
     *
     * <p>调用方传入的应当是"已包装"的会话（{@code ConcurrentWebSocketSessionDecorator}），
     * 这样后续所有下发都会经过装饰器的串行化与背压保护。
     *
     * @param roomId  房间号，调用方保证非空（handler 中缺省会回退为 room-1）
     * @param session 已建立的 WebSocket 会话（原始或装饰对象均可，建议传装饰对象）
     */
    public void register(String roomId, WebSocketSession session) {
        // 先写 attributes：注销时只有 session 可用，靠它才能立刻知道该去哪个房间集合摘除。
        // 装饰器会委托 getAttributes() 给原始 session，所以写入对两个对象都可见。
        session.getAttributes().put(ATTR_ROOM_ID, roomId);
        // computeIfAbsent：同一房间只在首个连接时创建 Set，之后复用。
        // ConcurrentHashMap 保证"同 key 的 computeIfAbsent"原子执行，
        // 多个连接并发首连同一房间时不会互相覆盖（不会丢连接）。
        boolean added = sessionsByRoom.computeIfAbsent(roomId, key -> ConcurrentHashMap.newKeySet()).add(session);
        // 仅在真正新增成功时累加，保证在线连接 gauge 与注册表内容一致（重复登记不会重复计数）。
        if (added) {
            this.metricsRegistry.recordConnectionOpened();
        }
    }

    /**
     * 从注册表移除会话（连接关闭时调用）。
     *
     * <p><b>为什么不能直接 {@code set.remove(session)}：</b>Spring 传给
     * {@code afterConnectionClosed} 的是<b>原始</b> {@code WebSocketSession}，
     * 而注册表里存的是 {@code ConcurrentWebSocketSessionDecorator}。装饰器的 equals 语义是
     * "装饰器与原始会话相等"（{@code WebSocketSessionDecorator#equals} 委托给 delegate），
     * 而 HashMap 系列在 remove 时调用的是<b>参数对象</b>的 equals（原始会话 equals 装饰器为 false），
     * 方向不对称，直接 remove 可能匹配不到。因此这里显式按"同一实例 或 相同 sessionId"匹配
     * （装饰器的 {@code getId()} 委托给原始会话，id 一定相同）。
     *
     * @param session 待移除的会话（原始或装饰对象均可）
     * @return 被移除会话所属的房间号；若该会话本就不在注册表中（重复关闭/从未注册）返回 {@code null}，
     *         调用方可据此判断"是否真的注销掉了一个连接"（例如指标计数只应在真正移除时累加）
     */
    public String remove(WebSocketSession session) {
        if (session == null) {
            return null;
        }
        String roomId = roomOf(session);
        if (roomId == null) {
            return null;
        }
        Set<WebSocketSession> sessions = sessionsByRoom.get(roomId);
        if (sessions == null) {
            return null;
        }
        // 找到注册表里真正存着的那个对象，再用它自身作为 key 调用 remove：
        // 这样 HashMap 比较时命中 `k == key`（同一实例），不依赖 equals 的方向。
        WebSocketSession stored = null;
        for (WebSocketSession candidate : sessions) {
            if (candidate == session || candidate.getId().equals(session.getId())) {
                stored = candidate;
                break;
            }
        }
        if (stored == null || !sessions.remove(stored)) {
            return null;
        }
        // 房间空了就把整个 key 摘掉，避免长时间运行时"已解散房间"的 key 无限堆积。
        // computeIfPresent 对同一 key 加锁：若此刻恰有新连接把会话加回同一房间，
        // isEmpty() 为 false 会保留该 key（不会把新连接所在的房间误删）。
        sessionsByRoom.computeIfPresent(roomId, (key, set) -> set.isEmpty() ? null : set);
        // 确实移除了一个会话，在线连接 gauge 同步 -1。
        this.metricsRegistry.recordConnectionClosed();
        return roomId;
    }

    /**
     * 返回某房间内所有会话的<b>快照</b>。
     *
     * <p>返回副本而不是内部集合：调用方（合批刷新线程）遍历期间可能正好有连接关闭或加入。
     * 快照迭代不会抛 ConcurrentModificationException；代价是可能包含"刚刚关闭"的会话，
     * 对其发送会失败——由发送方（FlushScheduler）捕获处理，这正是两层防御中"发送失败兜底"的一环。
     *
     * @param roomId 房间号
     * @return 不可变快照；房间不存在时返回空集合而不是 null，调用方无需判空
     */
    public Set<WebSocketSession> sessions(String roomId) {
        Set<WebSocketSession> sessions = sessionsByRoom.get(roomId);
        return sessions == null ? Set.of() : Set.copyOf(sessions);
    }

    /**
     * 反查会话所属房间号。
     *
     * @param session 任意 WebSocketSession（原始或装饰对象）
     * @return 注册时写入的房间号；未注册或属性缺失/类型不符时返回 {@code null}
     */
    public String roomOf(WebSocketSession session) {
        Object value = session.getAttributes().get(ATTR_ROOM_ID);
        return value instanceof String roomId ? roomId : null;
    }

    /**
     * 统计全局在线连接数（所有房间求和）。
     *
     * <p>遍历是弱一致的：统计瞬间如果有连接正在建立/关闭，结果可能不是精确快照。
     * 作为日志和监控指标用途完全可以接受——指标本来就是"观测值"而不是账本。
     *
     * @return 当前注册在表中的会话总数
     */
    public int connectionCount() {
        int count = 0;
        for (Set<WebSocketSession> sessions : sessionsByRoom.values()) {
            count += sessions.size();
        }
        return count;
    }

    /**
     * 返回所有房间所有在线会话的快照（合批刷新与指标推送需要“遍历全体”）。
     *
     * <p>返回的是新建列表，遍历期间连接增删不会影响本次遍历，也不会抛
     * ConcurrentModificationException。与 {@link #sessions(String)} 一样，快照可能包含
     * “刚刚关闭”的会话，发送失败的兜底由调用方负责。
     *
     * @return 全部在线会话；无连接时返回空列表
     */
    public List<WebSocketSession> allSessions() {
        List<WebSocketSession> all = new ArrayList<>();
        for (Set<WebSocketSession> sessions : sessionsByRoom.values()) {
            all.addAll(sessions);
        }
        return all;
    }
}
