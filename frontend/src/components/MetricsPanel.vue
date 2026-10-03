<script setup lang="ts">
/**
 * MetricsPanel.vue — 实时指标面板。
 *
 * 在整体数据流中的位置：
 *   useDanmakuSocket 收到 {"type":"metrics"} 帧 ──▶ store.setMetrics ──▶ 本组件 computed 重算 ──▶ 视图更新
 *
 * 设计取舍：不引入任何图表库，只是把 9 个数值以“标签 + 数值”的列表呈现。
 * 学习项目的关注点是链路与指标本身，而不是可视化花活；纯文本也更便于自动化断言。
 */
import { computed } from 'vue'
import { METRIC_FIELDS } from '../types'
import { useDanmakuStore } from '../stores/danmaku'

const { metrics } = useDanmakuStore()

/** 把指标对象摊平成可渲染的行（字段名即线上契约里的字段名）。 */
const rows = computed(() =>
  METRIC_FIELDS.map((field) => ({
    field,
    value: metrics.value[field],
  })),
)
</script>

<template>
  <aside class="metrics-panel" aria-label="实时指标面板">
    <h2 class="metrics-title">实时指标</h2>
    <dl class="metrics-list">
      <div v-for="row in rows" :key="row.field" class="metric-row">
        <dt class="metric-label">{{ row.field }}</dt>
        <dd class="metric-value" :data-metric="row.field">{{ row.value }}</dd>
      </div>
    </dl>
  </aside>
</template>

<style scoped>
.metrics-panel {
  background: var(--color-surface, #171a23);
  border: 1px solid var(--color-border, #2a2f3d);
  border-radius: var(--radius-md, 10px);
  padding: var(--space-4, 16px);
  overflow: auto;
}

.metrics-title {
  margin: 0 0 var(--space-4, 16px);
  font-size: 15px;
  font-weight: 600;
}

.metrics-list {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2, 8px);
}

.metric-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4, 16px);
}

.metric-label {
  color: var(--color-text-muted, #8b93a7);
  font-size: 12px;
  font-family: ui-monospace, 'Cascadia Code', Consolas, monospace;
}

.metric-value {
  margin: 0;
  font-size: 14px;
  font-variant-numeric: tabular-nums;
  color: var(--color-text, #e6e9f0);
}
</style>
