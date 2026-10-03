<script setup lang="ts">
/**
 * SimulationControls.vue — 模拟控制区（参数输入 + START/STOP + 状态轮询）。
 *
 * 在整体数据流中的位置：
 *   START ──▶ store.startSimulation ──▶ POST /api/sim/start（后端打开 N 条真实连接回连自身）
 *   每 1s ──▶ store.refreshStatus ──▶ GET /api/sim/status（驱动 running/连接数/已发送/失败）
 *
 * 交互约定：
 * - 运行中禁用 START（避免后端 409）；STOP 关闭全部虚拟连接；
 * - 后端返回 409/503 时不吞掉，展示可读错误，让“为什么没起来”一眼可见。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useDanmakuStore } from '../stores/danmaku'

const { simStatus, lastError, startSimulation, stopSimulation, refreshStatus } = useDanmakuStore()

/** 要打开的真实连接数（默认 100，对应 todo 12 的默认值）。 */
const connections = ref(100)
/** 每条连接发送的消息数。 */
const messagesPerConnection = ref(10)
/** 所有连接合计的发送速率（条/秒）。 */
const ratePerSecond = ref(500)
/** 目标房间号。 */
const roomId = ref('room-1')
/** 本地提交态：防止连点 START。 */
const submitting = ref(false)

let pollTimer = 0

/** 点击 START：提交模拟参数并把错误展示到面板。 */
async function onStart(): Promise<void> {
  if (submitting.value) {
    return
  }
  submitting.value = true
  try {
    await startSimulation({
      connections: connections.value,
      messagesPerConnection: messagesPerConnection.value,
      ratePerSecond: ratePerSecond.value,
      roomId: roomId.value,
    })
  } finally {
    submitting.value = false
  }
}

/** 点击 STOP：停止后端模拟并刷新状态。 */
async function onStop(): Promise<void> {
  await stopSimulation()
}

onMounted(() => {
  void refreshStatus()
  // 每秒轮询一次状态：后端模拟是异步后台任务，前端只能用轮询观测其进度。
  pollTimer = window.setInterval(() => {
    void refreshStatus()
  }, 1000)
})

onBeforeUnmount(() => {
  window.clearInterval(pollTimer)
})
</script>

<template>
  <footer class="sim-controls" aria-label="模拟控制区">
    <div class="field">
      <label for="sim-connections">connections</label>
      <input id="sim-connections" v-model.number="connections" type="number" min="1" max="5000" />
    </div>
    <div class="field">
      <label for="sim-messages">messagesPerConnection</label>
      <input id="sim-messages" v-model.number="messagesPerConnection" type="number" min="1" />
    </div>
    <div class="field">
      <label for="sim-rate">ratePerSecond</label>
      <input id="sim-rate" v-model.number="ratePerSecond" type="number" min="1" max="100000" />
    </div>
    <div class="field">
      <label for="sim-room">roomId</label>
      <input id="sim-room" v-model="roomId" type="text" />
    </div>

    <div class="actions">
      <button id="start-sim" class="btn btn-primary" :disabled="simStatus.running || submitting" @click="onStart">
        START
      </button>
      <button id="stop-sim" class="btn" :disabled="!simStatus.running" @click="onStop">STOP</button>
    </div>

    <div class="status" aria-live="polite">
      <span class="badge" :class="{ 'badge-running': simStatus.running }">
        {{ simStatus.running ? '运行中' : '空闲' }}
      </span>
      <span class="status-text">
        connections={{ simStatus.connections }} · sent={{ simStatus.sent }} · failed={{ simStatus.failed }}
      </span>
      <span v-if="lastError" class="error-text" role="alert">{{ lastError }}</span>
    </div>
  </footer>
</template>

<style scoped>
.sim-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--space-4, 16px);
  padding: var(--space-4, 16px);
  background: var(--color-surface, #171a23);
  border: 1px solid var(--color-border, #2a2f3d);
  border-radius: var(--radius-md, 10px);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field label {
  font-size: 12px;
  color: var(--color-text-muted, #8b93a7);
  font-family: ui-monospace, 'Cascadia Code', Consolas, monospace;
}

.field input {
  width: 140px;
  padding: 6px 8px;
  border: 1px solid var(--color-border, #2a2f3d);
  border-radius: 6px;
  background: #0f1117;
  color: var(--color-text, #e6e9f0);
  font-size: 13px;
}

.actions {
  display: flex;
  gap: var(--space-2, 8px);
}

.btn {
  padding: 8px 18px;
  border: 1px solid var(--color-border, #2a2f3d);
  border-radius: 6px;
  background: #222838;
  color: var(--color-text, #e6e9f0);
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
}

.btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.btn-primary {
  background: var(--color-accent, #4f8cff);
  border-color: var(--color-accent, #4f8cff);
  color: #fff;
}

.status {
  display: flex;
  align-items: center;
  gap: var(--space-2, 8px);
  margin-left: auto;
  font-size: 12px;
  color: var(--color-text-muted, #8b93a7);
}

.badge {
  padding: 2px 10px;
  border-radius: 999px;
  background: #2a2f3d;
  color: #c7cddb;
}

.badge-running {
  background: #1f6f43;
  color: #d6ffe8;
}

.status-text {
  font-family: ui-monospace, 'Cascadia Code', Consolas, monospace;
}

.error-text {
  color: #ff8f8f;
}
</style>
