<script setup lang="ts">
/**
 * App.vue — 应用外壳：把弹幕画布、指标面板、模拟控制区组合成完整页面，并建立实时连接。
 *
 * 在整体数据流中的位置：这是前端唯一的“装配点”。
 *   useDanmakuSocket() 建立并自动维持到 /ws/danmaku 的连接；
 *   DanmakuCanvas 消费 store 缓冲区做 canvas 渲染；
 *   MetricsPanel 展示 store 里的 9 项指标；
 *   SimulationControls 驱动后端模拟器并回显状态。
 */
import { computed } from 'vue'
import DanmakuCanvas from './components/DanmakuCanvas.vue'
import MetricsPanel from './components/MetricsPanel.vue'
import SimulationControls from './components/SimulationControls.vue'
import { useDanmakuSocket } from './composables/useDanmakuSocket'
import { useDanmakuStore } from './stores/danmaku'

// 建立连接：只需调用一次，连接与清理由组合式函数负责。
useDanmakuSocket()

const { connectionStatus } = useDanmakuStore()

/** 连接状态的中文展示文案。 */
const statusLabel = computed(() => {
  switch (connectionStatus.value) {
    case 'open':
      return '已连接'
    case 'connecting':
      return '连接中…'
    default:
      return '未连接'
  }
})
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <div class="app-heading">
        <h1 class="app-title">直播弹幕演示台</h1>
        <p class="app-subtitle">WebSocket + Redis 扇出 · Canvas 渲染 · 实时指标</p>
      </div>
      <span class="conn-badge" :class="`conn-${connectionStatus}`">WebSocket：{{ statusLabel }}</span>
    </header>

    <main class="app-main">
      <section class="canvas-slot" aria-label="弹幕画布">
        <DanmakuCanvas />
      </section>
      <MetricsPanel />
    </main>

    <SimulationControls />
  </div>
</template>

<style scoped>
/* 外壳级设计令牌：各组件通过 var(--...) 复用，避免颜色/间距魔法值散落。 */
.app-shell {
  --color-bg: #0f1117;
  --color-surface: #171a23;
  --color-border: #2a2f3d;
  --color-text: #e6e9f0;
  --color-text-muted: #8b93a7;
  --color-accent: #4f8cff;
  --space-2: 8px;
  --space-4: 16px;
  --space-6: 24px;
  --radius-md: 10px;

  box-sizing: border-box;
  min-height: 100vh;
  padding: var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  background: var(--color-bg);
  color: var(--color-text);
  font-family: 'Segoe UI', 'Microsoft YaHei', system-ui, sans-serif;
}

.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
}

.app-title {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
}

.app-subtitle {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--color-text-muted);
}

.conn-badge {
  padding: 4px 12px;
  border-radius: 999px;
  font-size: 12px;
  background: #2a2f3d;
  color: #c7cddb;
}

.conn-open {
  background: #1f6f43;
  color: #d6ffe8;
}

.conn-connecting {
  background: #6f5a1f;
  color: #ffeec2;
}

.app-main {
  flex: 1;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: var(--space-4);
  min-height: 480px;
}

.canvas-slot {
  min-width: 0;
  min-height: 420px;
  display: flex;
}

.canvas-slot > :deep(canvas) {
  flex: 1;
}

@media (max-width: 768px) {
  .app-main {
    grid-template-columns: 1fr;
  }
}
</style>
