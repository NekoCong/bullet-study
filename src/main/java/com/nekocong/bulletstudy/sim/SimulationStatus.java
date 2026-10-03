package com.nekocong.bulletstudy.sim;

/**
 * 模拟器运行状态（{@code GET /api/sim/status} 响应体）。
 *
 * <p><b>端到端链路中的位置：</b>
 * <pre>
 *   SimulationService 内部计数器 ──▶ 本记录 ──▶ SimulationController GET /api/sim/status
 * </pre>
 *
 * @param running     是否仍在发送（所有消息发完或已 STOP 后为 false）
 * @param connections 当前仍打开的真实客户端连接数
 * @param sent        已成功发送的消息条数
 * @param failed      失败的连接数 + 失败的发送次数（合并计数，便于一眼看出异常）
 */
public record SimulationStatus(boolean running, int connections, int sent, int failed) {
}
