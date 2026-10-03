/**
 * 实时 WebSocket 组合式函数：连接后端、解析下行帧、维护连接状态。
 *
 * 为什么用 @vueuse/core 的 useWebSocket：
 * - 内置自动重连（指数退避）与心跳，避免手写易错的定时器/重连状态机；
 * - 与 Vue 响应式无缝集成（status 可直接驱动 UI）。
 *
 * 重连策略：最多 10 次，延迟按 1s、2s、4s… 指数增长，封顶 30s——既给后端恢复时间，
 * 又不会在长时间宕机时疯狂重试。
 *
 * 心跳：每 15s 发一帧 `{"type":"ping"}`（后端按“未知类型”忽略，安全），
 * 5s 内没收到任何服务端消息就认为链路已死并触发重连；由于服务端每秒推送指标帧，
 * 正常情况下“任何入站消息”都会被 VueUse 视为心跳回应。
 */
import { useIntervalFn, useWebSocket } from '@vueuse/core'
import { watch } from 'vue'
import { parseFrame } from '../lib/parseFrame'
import { useDanmakuStore } from '../stores/danmaku'
import type { ConnectionStatus } from '../stores/danmaku'

/**
 * 建立（并自动维持）到弹幕服务的 WebSocket 连接。
 *
 * @param roomId 房间号，缺省 room-1（与后端默认订阅频道一致）
 * @returns VueUse 暴露的连接句柄（status/send/open/close）
 */
export function useDanmakuSocket(roomId = 'room-1') {
  const store = useDanmakuStore()
  const { status, send, open, close } = useWebSocket(buildSocketUrl(roomId), {
    immediate: true,
    autoReconnect: {
      retries: 10,
      delay: (retries: number) => Math.min(1000 * 2 ** (retries - 1), 30000),
    },
    heartbeat: {
      message: JSON.stringify({ type: 'ping' }),
      pongTimeout: 5000,
      // VueUse 15 的 heartbeat 用自定义 scheduler 控制发送间隔（不再有 interval 选项）。
      // useIntervalFn(cb, 15000) 表示每 15s 发一次心跳；immediate:false 表示不等首帧。
      scheduler: (cb: () => void) => useIntervalFn(cb, 15000, { immediate: false }),
    },
    onMessage: (_socket: WebSocket, event: MessageEvent) => {
      handleFrame(event.data)
    },
  })

  /**
   * 解析并路由一帧服务端数据。
   *
   * @param data 原始数据
   */
  function handleFrame(data: unknown): void {
    const parsed = parseFrame(data)
    if (parsed.kind === 'danmaku') {
      store.appendItems(parsed.items)
    } else if (parsed.kind === 'metrics') {
      store.setMetrics(parsed.metrics)
    }
    // unknown / malformed：静默忽略，协议演进时不至于崩溃
  }

  // 把底层发送能力注入 store，使 store.send(text) 可在任意组件中调用。
  store.setSender((text: string) => send(JSON.stringify({ type: 'danmaku', text })))

  // 将 VueUse 的字符串状态归一化为 UI 用的三种状态。
  watch(
    status,
    (value) => {
      store.setConnectionStatus(toConnectionStatus(value))
    },
    { immediate: true },
  )

  return { status, send, open, close }
}

/**
 * 构造 WebSocket 地址。
 *
 * 用当前页面的 host（而非硬编码后端地址）：开发时由 Vite 的 /ws 代理转发到后端，
 * 因此浏览器只需访问同源的 `/ws/danmaku`，组件里看不到 localhost:8080。
 *
 * @param roomId 房间号
 * @returns 完整 ws/wss 地址
 */
function buildSocketUrl(roomId: string): string {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${window.location.host}/ws/danmaku?roomId=${encodeURIComponent(roomId)}`
}

/**
 * 归一化连接状态。
 *
 * @param value VueUse 的 WebSocketStatus（OPEN/CONNECTING/CLOSING/CLOSED）
 * @returns UI 使用的三态
 */
function toConnectionStatus(value: string): ConnectionStatus {
  if (value === 'OPEN') {
    return 'open'
  }
  if (value === 'CONNECTING') {
    return 'connecting'
  }
  return 'closed'
}
