package com.nekocong.bulletstudy.metrics;

/**
 * 指标快照：某一时刻下 {@link MetricsRegistry} 的只读视图。
 *
 * <p><b>端到端链路中的位置：</b>
 * <pre>
 *   MetricsRegistry.snapshot() ──▶ 本记录
 *        ├── MetricsController GET /api/metrics（HTTP 拉取，始终 200）
 *        └── MetricsBroadcaster（每秒经 WebSocket 推送 {"type":"metrics", ...} 帧）
 * </pre>
 *
 * <p><b>为什么用 record：</b>它是不可变的值对象，字段即 JSON 字段名与顺序，
 * 天然与“wire contract”里的 9 个字段一一对应，避免 getter/setter 样板与序列化歧义。
 * 注意字段顺序<b>必须</b>与契约一致：connections, totalReceived, totalPublished,
 * totalBroadcast, publishRate, broadcastRate, avgLatencyMs, p99LatencyMs, dropped。
 *
 * @param connections    当前在线连接数（gauge，连接建立 +1、关闭 -1）
 * @param totalReceived  累计“被服务端接收并解析”的弹幕条数
 * @param totalPublished 累计“发布到 Redis 频道”的条数
 * @param totalBroadcast 累计“入队到某个会话待发送”的条数（按收件人计，非消息条数）
 * @param publishRate    最近 1 秒的发布速率（条/秒，滑动窗口）
 * @param broadcastRate  最近 1 秒的广播速率（收件人次/秒，滑动窗口）
 * @param avgLatencyMs   serverTs - ts 的平均值（毫秒；无有效样本时为 0）
 * @param p99LatencyMs   serverTs - ts 的 P99（毫秒；无有效样本时为 0）
 * @param dropped        业务合批队列因超过上限而丢弃的最旧消息条数
 */
public record MetricsSnapshot(
        long connections,
        long totalReceived,
        long totalPublished,
        long totalBroadcast,
        long publishRate,
        long broadcastRate,
        long avgLatencyMs,
        long p99LatencyMs,
        long dropped) {
}
