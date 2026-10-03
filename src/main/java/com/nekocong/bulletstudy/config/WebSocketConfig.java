package com.nekocong.bulletstudy.config;

import com.nekocong.bulletstudy.realtime.DanmakuWebSocketHandler;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 装配类：把弹幕网关挂载到 {@code /ws/danmaku}。
 *
 * <p><b>端到端链路中的位置：</b>这是整条实时链路的"接线处"。
 * {@link DanmakuWebSocketHandler} 本身只是处理逻辑，必须在这里注册到 Spring 的
 * WebSocket 基础设施上，Spring MVC 才会把该路径的 HTTP Upgrade 请求交给它：
 * <pre>
 *   浏览器 /ws/danmaku?roomId=room-1 ──▶ Spring WebSocket 基础设施 ──▶ DanmakuWebSocketHandler
 * </pre>
 *
 * <p><b>为什么用 {@code @EnableWebSocket} 而不是 {@code @EnableWebSocketMessageBroker}：</b>
 * 本项目刻意使用"原生 WebSocket + JSON 文本帧"（不用 STOMP）。{@code @EnableWebSocket}
 * 只开启底层 WebSocket 支持，不引入消息代理、订阅地址等概念，更贴近学习目标；
 * STOMP 属于被明确排除的范围。
 *
 * @see DanmakuWebSocketHandler
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /** 弹幕 WebSocket 端点路径（与前端 Vite 代理的 {@code /ws} 前缀、wire contract 一致）。 */
    private static final String DANMAKU_ENDPOINT = "/ws/danmaku";

    private final DanmakuWebSocketHandler danmakuWebSocketHandler;

    /**
     * 构造器注入：由 Spring 把已经装配好依赖与配置的网关实例传进来。
     *
     * @param danmakuWebSocketHandler 弹幕 WebSocket 网关（{@code @Component}）
     */
    public WebSocketConfig(DanmakuWebSocketHandler danmakuWebSocketHandler) {
        this.danmakuWebSocketHandler = danmakuWebSocketHandler;
    }

    /**
     * 注册 WebSocket 处理器：路径 {@code /ws/danmaku} 上的握手与消息都交给弹幕网关。
     *
     * <p><b>为什么放开来源校验（allowedOriginPatterns）：</b>开发时浏览器访问的是 Vite
     * 开发服务器（{@code http://localhost:5173}），它与后端（{@code http://localhost:8080}）
     * <b>不同源</b>；Vite 代理配置了 {@code changeOrigin: true}，转发到后端时 Host 已被改写为
     * localhost:8080，而浏览器带上的 {@code Origin} 仍是 localhost:5173。Spring 默认的
     * {@code OriginHandshakeInterceptor} 做同源比对，这种情况下会直接 403 拒绝握手。
     * 本项目是本地学习演示：无登录态、无用户数据、不对外网暴露，因此允许任意来源换取开发便利；
     * 若将来上线，应改为明确的白名单来源（例如只允许前端域名）。
     *
     * <p>注意：模拟器（JDK HttpClient）与非浏览器客户端不发送 {@code Origin} 头，本就不受该校验影响。
     *
     * @param registry Spring 提供的 WebSocket 处理器注册表
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(this.danmakuWebSocketHandler, DANMAKU_ENDPOINT)
                .setAllowedOriginPatterns("*");
    }
}
