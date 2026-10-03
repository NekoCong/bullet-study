package com.nekocong.bulletstudy.danmaku;

/**
 * 一条弹幕消息（同时用于 Redis payload 与下发给客户端的帧元素）。
 *
 * <p><b>端到端链路中的位置：</b>
 * <pre>
 *   BroadcastService.ingest 构造 ──▶ 序列化为 JSON 发布到 Redis ──▶ 订阅端反序列化
 *        │                                                              │
 *        └──────────────▶ 合批进 FlushScheduler 的 {"type":"danmaku","items":[...]} 帧
 * </pre>
 *
 * <p><b>为什么用 record：</b>字段固定、不可变、天然是 Jackson 可序列化的数据载体，
 * 且与 wire contract 的 items 元素字段一一对应，避免字段漂移。
 *
 * @param type     帧/元素类型，恒为 {@code "danmaku"}（便于前端按顶层或元素级类型区分）
 * @param roomId   房间号；来源是连接 URI，而不是消息体（wire contract）
 * @param text     弹幕文本；服务端在 ingest 时已裁剪/校验（非空白、最长 200 字符）
 * @param ts       客户端时间戳（毫秒）；客户端未提供时服务端用接收时刻填充
 * @param serverTs 服务端接收时间戳（毫秒）；延迟 = serverTs - ts，用于指标统计
 */
public record DanmakuMessage(
        String type,
        String roomId,
        String text,
        long ts,
        long serverTs) {

    /** 固定的弹幕类型常量，避免各处硬编码字符串。 */
    public static final String TYPE = "danmaku";

    /**
     * 构造一条标准弹幕（自动补齐 {@code type}）。
     *
     * @param roomId   房间号
     * @param text     已处理的文本
     * @param ts       客户端时间戳
     * @param serverTs 服务端接收时间戳
     * @return 类型为 {@code "danmaku"} 的消息
     */
    public static DanmakuMessage of(String roomId, String text, long ts, long serverTs) {
        return new DanmakuMessage(TYPE, roomId, text, ts, serverTs);
    }
}
