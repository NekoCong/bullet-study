/**
 * 下行帧解析器（纯函数，无框架依赖）。
 *
 * 为什么独立成文件：解析逻辑是前端最需要被验证的“纯逻辑”，把它与 Vue/VueUse 解耦后，
 * 可以在 Node 里直接喂字符串做断言（见 todo 9 的验证脚本），也便于复用与测试。
 *
 * 设计原则：
 * - 只按“顶层 type”分派；未知类型返回 unknown，非法 JSON 返回 malformed，绝不抛异常；
 * - 不用 `any`：用 `unknown` + 类型守卫收窄，保证严格模式下类型安全；
 * - 数值字段做防御式归一化（非有限数一律回退 0），避免后端字段缺失导致 NaN 污染界面。
 */
import type { DanmakuItem, MetricsPayload } from '../types'

/** 解析结果：判别联合，调用方必须显式处理每种情况。 */
export type ParsedFrame =
  | { kind: 'danmaku'; items: DanmakuItem[] }
  | { kind: 'metrics'; metrics: MetricsPayload }
  | { kind: 'unknown' }
  | { kind: 'malformed' }

/**
 * 解析一条文本帧。
 *
 * @param raw WebSocket 收到的原始数据（期望为字符串）
 * @returns 判别联合结果；unknown 表示类型未知，malformed 表示不是合法 JSON
 */
export function parseFrame(raw: unknown): ParsedFrame {
  if (typeof raw !== 'string') {
    return { kind: 'malformed' }
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return { kind: 'malformed' }
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return { kind: 'malformed' }
  }
  const frame = parsed as Record<string, unknown>
  if (frame.type === 'danmaku') {
    const items = Array.isArray(frame.items) ? frame.items.filter(isDanmakuItem) : []
    return { kind: 'danmaku', items }
  }
  if (frame.type === 'metrics') {
    return { kind: 'metrics', metrics: pickMetrics(frame) }
  }
  return { kind: 'unknown' }
}

/**
 * 类型守卫：判断一个值是否是合法的弹幕元素。
 *
 * @param value 待判断值
 * @returns 满足 {@link DanmakuItem} 结构时为 true
 */
function isDanmakuItem(value: unknown): value is DanmakuItem {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const item = value as Record<string, unknown>
  return (
    typeof item.roomId === 'string' &&
    typeof item.text === 'string' &&
    typeof item.ts === 'number' &&
    typeof item.serverTs === 'number'
  )
}

/**
 * 从指标帧中提取 9 个数值字段（缺失/非法一律回退 0）。
 *
 * @param frame 已确认 `type === 'metrics'` 的对象
 * @returns 归一化后的指标载荷
 */
function pickMetrics(frame: Record<string, unknown>): MetricsPayload {
  return {
    connections: toNumber(frame.connections),
    totalReceived: toNumber(frame.totalReceived),
    totalPublished: toNumber(frame.totalPublished),
    totalBroadcast: toNumber(frame.totalBroadcast),
    publishRate: toNumber(frame.publishRate),
    broadcastRate: toNumber(frame.broadcastRate),
    avgLatencyMs: toNumber(frame.avgLatencyMs),
    p99LatencyMs: toNumber(frame.p99LatencyMs),
    dropped: toNumber(frame.dropped),
  }
}

/**
 * 防御式数值转换。
 *
 * @param value 任意值
 * @returns 有限数值本身；否则 0
 */
function toNumber(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0
}
