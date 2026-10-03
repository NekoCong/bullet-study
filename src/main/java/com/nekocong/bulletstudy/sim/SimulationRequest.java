package com.nekocong.bulletstudy.sim;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 模拟器启动参数（{@code POST /api/sim/start} 请求体）。
 *
 * <p><b>端到端链路中的位置：</b>由前端“模拟控制区”或 curl 提交，驱动服务端回连自身：
 * <pre>
 *   POST /api/sim/start ──@Valid 校验──▶ 本记录 ──▶ SimulationService.start
 * </pre>
 *
 * <p><b>为什么参数要有上限：</b>模拟器会在本进程内打开 N 条真实 WebSocket 连接（回连自身），
 * 客户端 + 服务端合计约 2N 个 socket。若 N/速率无上限，一次误操作就能耗尽文件描述符或堆。
 * 因此用 Bean Validation 在入口处限幅：连接数 ≤ 5000、速率 ≤ 100000/s。
 *
 * @param connections           要打开的真实连接数（即虚拟观众数）
 * @param messagesPerConnection 每条连接发送的消息条数
 * @param ratePerSecond         所有连接合计的发送速率（条/秒）
 * @param roomId                目标房间；为空时回退 {@code room-1}
 */
public record SimulationRequest(
        @Min(1) @Max(5000) int connections,
        @Min(1) int messagesPerConnection,
        @Min(1) @Max(100000) int ratePerSecond,
        String roomId) {

    /** 默认房间号，与订阅端固定订阅的频道保持一致。 */
    public static final String DEFAULT_ROOM_ID = "room-1";

    /**
     * 归一化房间号。
     *
     * @return 非空白 roomId；为空时返回 {@link #DEFAULT_ROOM_ID}
     */
    public String resolvedRoomId() {
        if (this.roomId == null || this.roomId.isBlank()) {
            return DEFAULT_ROOM_ID;
        }
        return this.roomId.trim();
    }
}
