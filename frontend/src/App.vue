<script setup lang="ts">
/**
 * App.vue — 应用外壳（本 todo 只搭骨架，不实现任何弹幕/网络/渲染逻辑）。
 *
 * 在整体数据流中的位置：
 *   后续 todo 会在这里组合三块 UI——canvas 弹幕画布（todo 10）、实时指标面板（todo 11）、
 *   模拟控制区（todo 12）——并由 useDanmakuSocket（todo 9）统一管理 WebSocket 连接。
 *   当前文件仅提供页面结构与三个占位容器，保证 `npm run build` 可编译通过。
 */
const appTitle = '直播弹幕演示台'
const appSubtitle = 'WebSocket + Redis 扇出 · Canvas 渲染 · 实时指标（脚手架阶段）'
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <h1 class="app-title">{{ appTitle }}</h1>
      <p class="app-subtitle">{{ appSubtitle }}</p>
    </header>

    <main class="app-main">
      <!-- 占位：todo 10 的 <DanmakuCanvas /> 将挂载到这里（弹幕滚动画布） -->
      <section id="danmaku-canvas-slot" class="slot slot-canvas" aria-label="弹幕画布占位">
        <span class="slot-label">弹幕画布（canvas）· 待 todo 10 接入</span>
      </section>

      <!-- 占位：todo 11 的 <MetricsPanel /> 将挂载到这里（9 项实时指标） -->
      <aside id="metrics-slot" class="slot slot-metrics" aria-label="指标面板占位">
        <span class="slot-label">实时指标面板 · 待 todo 11 接入</span>
      </aside>
    </main>

    <footer id="controls-slot" class="slot slot-controls" aria-label="模拟控制区占位">
      <span class="slot-label">模拟控制区（START / STOP）· 待 todo 12 接入</span>
    </footer>
  </div>
</template>

<style scoped>
/* 外壳级设计令牌：后续组件的颜色/间距/圆角统一从这里取值，避免各组件散落魔法值。 */
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
  flex-direction: column;
  gap: var(--space-2);
}

.app-title {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
}

.app-subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--color-text-muted);
}

.app-main {
  flex: 1;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 260px;
  gap: var(--space-4);
  min-height: 480px;
}

/* 占位容器样式：仅用于骨架阶段显示区域边界，后续组件会替换内部内容。 */
.slot {
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  padding: var(--space-4);
}

.slot-canvas {
  min-height: 420px;
}

.slot-label {
  color: var(--color-text-muted);
  font-size: 13px;
}

.slot-controls {
  min-height: 72px;
}

@media (max-width: 768px) {
  .app-main {
    grid-template-columns: 1fr;
  }
}
</style>
