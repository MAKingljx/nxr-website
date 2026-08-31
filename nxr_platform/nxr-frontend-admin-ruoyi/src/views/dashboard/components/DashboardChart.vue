<template>
  <section class="dashboard-panel chart-panel">
    <header class="panel-header">
      <div>
        <p class="panel-kicker">{{ kicker }}</p>
        <h2>{{ title }}</h2>
      </div>
      <span v-if="meta" class="panel-meta">{{ meta }}</span>
    </header>

    <div class="chart-body">
      <div ref="chartElement" class="chart-canvas" :class="{ 'chart-canvas--empty': empty }"></div>
      <el-empty v-if="empty" class="chart-empty" :description="emptyText" :image-size="54" />
    </div>
  </section>
</template>

<script setup>
import * as echarts from 'echarts'

const props = defineProps({
  kicker: { type: String, required: true },
  title: { type: String, required: true },
  meta: { type: String, default: '' },
  option: { type: Object, required: true },
  empty: { type: Boolean, default: false },
  emptyText: { type: String, default: '' }
})

const chartElement = ref(null)
let chartInstance
let resizeObserver

function renderChart() {
  if (!chartElement.value || props.empty) return
  if (!chartInstance) {
    chartInstance = echarts.init(chartElement.value, null, { renderer: 'svg' })
  }
  chartInstance.setOption(props.option, true)
}

onMounted(() => {
  renderChart()
  resizeObserver = new ResizeObserver(() => chartInstance?.resize())
  resizeObserver.observe(chartElement.value)
})

watch(
  () => [props.option, props.empty],
  async () => {
    await nextTick()
    if (props.empty) {
      chartInstance?.clear()
      return
    }
    renderChart()
  },
  { deep: true }
)

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chartInstance?.dispose()
})
</script>

<style scoped>
.dashboard-panel {
  min-width: 0;
  border: 1px solid var(--nxr-border);
  border-radius: 8px;
  background: var(--nxr-surface);
}

.panel-header {
  display: flex;
  min-height: 70px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 15px 20px;
  border-bottom: 1px solid var(--nxr-border-subtle);
}

.panel-kicker {
  margin: 0 0 4px;
  color: var(--nxr-accent);
  font-size: 10px;
  font-weight: 800;
}

.panel-header h2 {
  margin: 0;
  color: var(--nxr-text-strong);
  font-size: 17px;
  line-height: 1.2;
}

.panel-meta {
  color: var(--nxr-text-faint);
  font-size: 11px;
  white-space: nowrap;
}

.chart-body {
  position: relative;
  min-height: 292px;
}

.chart-canvas {
  width: 100%;
  height: 292px;
}

.chart-canvas--empty {
  visibility: hidden;
}

.chart-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
