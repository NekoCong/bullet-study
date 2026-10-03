/**
 * 前端全局状态（单例 store）。
 *
 * 为什么用“模块级单例 + useXxx() 返回”而不是 Pinia：本学习项目刻意不引入额外状态库
 * （Must-NOT 约束）。模块顶层声明的 ref 天然全局唯一，任何组件调用 useDanmakuStore() 拿到的
 * 都是同一份状态，等价于一个极简 store，且不增加依赖与心智负担。
 *
 * 数据流：
 *   useDanmakuSocket 解析下行帧 ──▶ appendItems / setMetrics
 *   DanmakuCanvas 每帧 takePending() 取走并清空缓冲区
 *   MetricsPanel 订阅 metrics 自动刷新
 *   SimulationControls 调用 startSimulation / stopSimulation / refreshStatus
 */
import { ref, shallowRef } from 'vue'
import type { DanmakuItem, MetricsPayload, SimParams, SimStatus } from '../types'
import { ZERO_METRICS } from '../types'

/** 弹幕缓冲区上限：只保留最近 N 条，避免洪峰下内存无限增长（渲染侧只消费最新内容）。 */
const BUFFER_CAP = 500

/** WebSocket 连接状态（对 VueUse 的字符串状态做了归一化，便于 UI 展示）。 */
export type ConnectionStatus = 'connecting' | 'open' | 'closed'

/**
 * 弹幕环形缓冲区。用 shallowRef：数组本身被整体替换时通知依赖，
 * 但不深度代理数组元素——弹幕是高吞吐数据，深度响应式是纯粹的浪费。
 */
const buffer = shallowRef<DanmakuItem[]>([])

/** 最新一次指标快照；初值全 0，保证面板在任何帧到达前也不显示 NaN/空白。 */
const metrics = ref<MetricsPayload>({ ...ZERO_METRICS })

/** WebSocket 连接状态。 */
const connectionStatus = ref<ConnectionStatus>('closed')

/** 模拟器状态（由 /api/sim/status 轮询刷新）。 */
const simStatus = ref<SimStatus>({ running: false, connections: 0, sent: 0, failed: 0 })

/** 最近一次操作错误（例如 Redis 不可用、409 冲突），供 UI 展示。 */
const lastError = ref<string | null>(null)

/** 由 composable 注入的底层发送函数（store 本身不持有 WebSocket 实例）。 */
let sender: ((text: string) => boolean) | null = null

/**
 * 追加弹幕到缓冲区（超出上限时丢弃最旧的部分）。
 *
 * @param items 本次收到的弹幕
 */
function appendItems(items: DanmakuItem[]): void {
  if (items.length === 0) {
    return
  }
  const combined = buffer.value.concat(items)
  buffer.value = combined.length > BUFFER_CAP ? combined.slice(combined.length - BUFFER_CAP) : combined
}

/**
 * 取走并清空当前缓冲（渲染循环每帧调用）。
 *
 * @returns 自上次取走以来累积的弹幕；无数据时返回空数组
 */
function takePending(): DanmakuItem[] {
  const pending = buffer.value
  if (pending.length > 0) {
    buffer.value = []
  }
  return pending
}

/**
 * 用最新指标帧替换当前指标。
 *
 * @param next 指标载荷
 */
function setMetrics(next: MetricsPayload): void {
  metrics.value = next
}

/**
 * 更新连接状态。
 *
 * @param status 归一化后的状态
 */
function setConnectionStatus(status: ConnectionStatus): void {
  connectionStatus.value = status
}

/**
 * 注入发送函数（由 useDanmakuSocket 在建立连接时调用）。
 *
 * @param fn 文本发送函数；传 null 表示断开注入
 */
function setSender(fn: ((text: string) => boolean) | null): void {
  sender = fn
}

/**
 * 发送一条弹幕。
 *
 * @param text 文本；空白会被忽略
 * @returns 是否成功交给底层发送（未连接/空白为 false）
 */
function send(text: string): boolean {
  const trimmed = text.trim()
  if (!sender || trimmed.length === 0) {
    return false
  }
  return sender(trimmed)
}

/**
 * 启动服务端模拟器。
 *
 * @param params 启动参数
 * @returns 成功返回 null；失败返回可展示的错误文案（同时写入 lastError）
 */
async function startSimulation(params: SimParams): Promise<string | null> {
  lastError.value = null
  try {
    const response = await fetch('/api/sim/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params),
    })
    if (response.status === 202) {
      await refreshStatus()
      return null
    }
    const message =
      response.status === 409
        ? '已有模拟在运行（后端返回 409）'
        : response.status === 503
          ? 'Redis 不可用，请先启动 Redis（/api/health 返回 503）'
          : `启动失败：HTTP ${response.status}`
    lastError.value = message
    return message
  } catch (error) {
    const message = error instanceof Error ? error.message : '网络请求失败'
    lastError.value = message
    return message
  }
}

/**
 * 停止服务端模拟器并刷新状态。
 */
async function stopSimulation(): Promise<void> {
  try {
    await fetch('/api/sim/stop', { method: 'POST' })
  } catch {
    // 请求失败也继续刷新状态：真实状态以随后的一次轮询为准，避免 UI 卡在“运行中”。
  }
  await refreshStatus()
}

/**
 * 拉取模拟器状态。
 *
 * @returns 最新状态；请求失败返回 null（不覆盖旧状态以外的任何东西）
 */
async function refreshStatus(): Promise<SimStatus | null> {
  try {
    const response = await fetch('/api/sim/status')
    if (!response.ok) {
      return null
    }
    const status = (await response.json()) as SimStatus
    simStatus.value = status
    return status
  } catch {
    return null
  }
}

/**
 * 获取 store（单例）。
 *
 * @returns 状态引用与操作函数集合
 */
export function useDanmakuStore() {
  return {
    buffer,
    metrics,
    connectionStatus,
    simStatus,
    lastError,
    appendItems,
    takePending,
    setMetrics,
    setConnectionStatus,
    setSender,
    send,
    startSimulation,
    stopSimulation,
    refreshStatus,
  }
}

/** store 的类型别名，便于需要显式标注时复用。 */
export type DanmakuStore = ReturnType<typeof useDanmakuStore>
