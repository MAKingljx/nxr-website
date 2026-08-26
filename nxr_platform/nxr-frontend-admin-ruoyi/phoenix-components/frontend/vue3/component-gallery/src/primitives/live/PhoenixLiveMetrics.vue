<script setup lang="ts">
import { computed } from 'vue'
import { safeAmount, safeCount } from './safety'

export type PhoenixLiveMetricKind = 'count' | 'currency' | 'percent' | 'duration' | 'number'

export interface PhoenixLiveMetric {
  key: string
  label: string
  value: number
  kind?: PhoenixLiveMetricKind
  currency?: string
  unit?: string
  trend?: number
  status?: 'default' | 'success' | 'warning' | 'danger'
  disabled?: boolean
}

const props = withDefaults(defineProps<{
  metrics: PhoenixLiveMetric[]
  title?: string
  locale?: string
  currency?: string
  loading?: boolean
  disabled?: boolean
  showRefresh?: boolean
  emptyText?: string
}>(), {
  title: '直播指标',
  locale: 'zh-CN',
  currency: 'CNY',
  loading: false,
  disabled: false,
  showRefresh: false,
  emptyText: '暂无直播指标',
})

const emit = defineEmits<{
  select: [metric: PhoenixLiveMetric]
  refresh: []
}>()

const uniqueMetrics = computed(() => {
  const seen = new Set<string>()
  return props.metrics.filter((metric) => metric.key && !seen.has(metric.key) && Boolean(seen.add(metric.key)))
})

function safeNumber(value: number) {
  if (!Number.isFinite(value)) return 0
  return Math.min(999_999_999_999, Math.max(0, value))
}

function formatCurrency(value: number, currency: string) {
  const amount = safeAmount(value)
  try {
    return new Intl.NumberFormat(props.locale, { style: 'currency', currency }).format(amount)
  } catch {
    return `${currency} ${amount.toFixed(2)}`
  }
}

function formatDuration(value: number) {
  const seconds = safeCount(value, 359_999_999)
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const rest = seconds % 60
  return `${hours ? `${hours}时` : ''}${minutes ? `${minutes}分` : ''}${rest}秒`
}

function format(metric: PhoenixLiveMetric) {
  const kind = metric.kind ?? 'number'
  if (kind === 'count') return safeCount(metric.value).toLocaleString(props.locale)
  if (kind === 'currency') return formatCurrency(metric.value, metric.currency || props.currency)
  if (kind === 'percent') return `${Math.min(100, safeNumber(metric.value)).toFixed(1)}%`
  if (kind === 'duration') return formatDuration(metric.value)
  return safeNumber(metric.value).toLocaleString(props.locale, { maximumFractionDigits: 2 })
}

function trend(value?: number) {
  if (!Number.isFinite(value)) return 0
  return Math.min(999, Math.max(-999, value ?? 0))
}
</script>

<template>
  <section class="px-live-metrics" :aria-label="title" :aria-busy="loading">
    <header>
      <h3>{{ title }}</h3>
      <button v-if="showRefresh" type="button" :disabled="disabled || loading" @click="emit('refresh')">刷新</button>
    </header>
    <p v-if="loading" class="px-live-state" role="status">指标加载中</p>
    <p v-else-if="!uniqueMetrics.length" class="px-live-state" role="status">{{ emptyText }}</p>
    <div v-else class="px-live-metrics__grid" role="list">
      <div v-for="metric in uniqueMetrics" :key="metric.key" role="listitem" :class="`is-${metric.status || 'default'}`">
        <button type="button" :disabled="disabled || metric.disabled" :aria-label="`${metric.label}：${format(metric)}${metric.unit || ''}`" @click="emit('select', metric)">
          <span class="px-live-metrics__label">{{ metric.label }}</span>
          <strong>{{ format(metric) }}<small v-if="metric.unit">{{ metric.unit }}</small></strong>
          <span v-if="metric.trend !== undefined" :class="trend(metric.trend) >= 0 ? 'is-up' : 'is-down'">{{ trend(metric.trend) >= 0 ? '↑' : '↓' }} {{ Math.abs(trend(metric.trend)).toFixed(1) }}%</span>
        </button>
      </div>
    </div>
  </section>
</template>
