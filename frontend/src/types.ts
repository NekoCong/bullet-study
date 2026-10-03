/**
 * 前后端共享的“线协议”（wire contract）类型定义。
 *
 * 为什么单独抽一个文件：这些形状是前后端唯一的契约来源，集中定义能让类型检查与文档天然一致。
 * 所有类型都是结构化数据，不含运行期依赖（仅 ZERO_METRICS / 字段清单是常量），
 * 因此可以被纯函数解析器与 Vue 组件共同复用，也便于在 Node 里单独做单元级验证。
 */

/** 一条弹幕（对应服务端 `{"type":"danmaku","items":[...]}` 里的元素）。 */
export interface DanmakuItem {
  type: 'danmaku'
  /** 房间号：由连接 URI 决定，消息体本身不带。 */
  roomId: string
  /** 弹幕文本（服务端已裁剪到 200 字符内）。 */
  text: string
  /** 客户端时间戳（毫秒）。 */
  ts: number
  /** 服务端接收时间戳（毫秒）。 */
  serverTs: number
}

/** 9 项实时指标，与 `/api/metrics` 及 `{"type":"metrics"}` 帧的字段完全一致。 */
export interface MetricsPayload {
  /** 当前在线连接数。 */
  connections: number
  /** 累计接收的弹幕条数。 */
  totalReceived: number
  /** 累计发布到 Redis 的条数。 */
  totalPublished: number
  /** 累计按收件人入队的次数。 */
  totalBroadcast: number
  /** 最近 1 秒发布速率（条/秒）。 */
  publishRate: number
  /** 最近 1 秒广播速率（收件人次/秒）。 */
  broadcastRate: number
  /** 平均延迟（毫秒）。 */
  avgLatencyMs: number
  /** P99 延迟（毫秒）。 */
  p99LatencyMs: number
  /** 因合批队列超限丢弃的条数。 */
  dropped: number
}

/** 弹幕帧：`{"type":"danmaku","items":[...]}`。 */
export interface DanmakuFrame {
  type: 'danmaku'
  items: DanmakuItem[]
}

/** 指标帧：`{"type":"metrics", ...9 字段...}`（字段扁平）。 */
export interface MetricsFrame extends MetricsPayload {
  type: 'metrics'
}

/**
 * 服务端下行帧的判别联合（discriminated union）。
 *
 * 为什么用联合而不是宽泛对象：`type` 是判别字段，TS 在 `switch/if` 收敛后能自动收窄类型，
 * 避免使用 `any`，也让“未知类型”在类型层面就必须显式处理。
 */
export type ServerFrame = DanmakuFrame | MetricsFrame

/** 指标字段清单（顺序即 wire contract 顺序），供指标面板遍历渲染。 */
export const METRIC_FIELDS = [
  'connections',
  'totalReceived',
  'totalPublished',
  'totalBroadcast',
  'publishRate',
  'broadcastRate',
  'avgLatencyMs',
  'p99LatencyMs',
  'dropped',
] as const

/** 指标字段名的字面量类型。 */
export type MetricField = (typeof METRIC_FIELDS)[number]

/** 指标初始值：全 0，保证面板在收到任何帧之前也不会出现 NaN/空白。 */
export const ZERO_METRICS: MetricsPayload = {
  connections: 0,
  totalReceived: 0,
  totalPublished: 0,
  totalBroadcast: 0,
  publishRate: 0,
  broadcastRate: 0,
  avgLatencyMs: 0,
  p99LatencyMs: 0,
  dropped: 0,
}

/** 模拟器启动参数（POST /api/sim/start 请求体）。 */
export interface SimParams {
  connections: number
  messagesPerConnection: number
  ratePerSecond: number
  roomId: string
}

/** 模拟器状态（GET /api/sim/status 响应体）。 */
export interface SimStatus {
  running: boolean
  connections: number
  sent: number
  failed: number
}
