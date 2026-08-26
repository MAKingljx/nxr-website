<script setup lang="ts">
import { computed } from 'vue'
import type { PhoenixAnalyticsAppearance, PhoenixAnalyticsValueKind, PhoenixChartDatum } from './types'
import { formatAnalyticsValue, safeColor, safeDimension, safePositive, uniqueByKey } from './utils'

interface DonutSegment {
  item: PhoenixChartDatum
  value: number
  color: string
  dash: number
  gap: number
  offset: number
  percent: number
}

const props = withDefaults(defineProps<{
  data: PhoenixChartDatum[]
  title?: string
  centerLabel?: string
  selectedKey?: string
  thickness?: number
  valueKind?: PhoenixAnalyticsValueKind
  locale?: string
  currency?: string
  appearance?: PhoenixAnalyticsAppearance
  emptyText?: string
}>(), {
  title: '占比分析',
  centerLabel: '合计',
  selectedKey: '',
  thickness: 22,
  valueKind: 'number',
  locale: 'zh-CN',
  currency: 'CNY',
  appearance: 'modern',
  emptyText: '暂无环形图数据',
})

const emit = defineEmits<{
  'update:selectedKey': [key: string]
  select: [item: PhoenixChartDatum, percent: number]
}>()

const radius = 70
const circumference = 2 * Math.PI * radius
const safeThickness = computed(() => safeDimension(props.thickness, 22, 8, 42))
const normalizedData = computed(() => uniqueByKey(props.data).map((item, index) => ({ item, value: safePositive(item.value), color: safeColor(item.color, index) })))
const total = computed(() => normalizedData.value.reduce((sum, { value }) => sum + value, 0))
const segments = computed<DonutSegment[]>(() => {
  let consumed = 0
  return normalizedData.value.map(({ item, value, color }) => {
    const percent = total.value ? (value / total.value) * 100 : 0
    const length = total.value ? (value / total.value) * circumference : 0
    const gap = Math.min(3, length)
    const segment = { item, value, color, dash: Math.max(0, length - gap), gap: circumference - Math.max(0, length - gap), offset: -consumed, percent }
    consumed += length
    return segment
  })
})

function format(value: number) {
  return formatAnalyticsValue(value, props.valueKind, props.locale, props.currency)
}

function select(segment: DonutSegment) {
  if (segment.item.disabled) return
  emit('update:selectedKey', segment.item.key)
  emit('select', segment.item, segment.percent)
}
</script>

<template>
  <section class="px-donut-chart" :class="`is-${appearance}`" :aria-label="title">
    <header class="px-analytics-header"><h3>{{ title }}</h3><slot name="actions"></slot></header>
    <p v-if="!total" class="px-analytics-empty" role="status">{{ emptyText }}</p>
    <div v-else class="px-donut-chart__body">
      <svg viewBox="0 0 200 200" role="group" :aria-label="title">
        <title>{{ title }}</title>
        <desc>{{ segments.map((segment) => `${segment.item.label} ${segment.percent.toFixed(1)}%`).join('，') }}</desc>
        <circle cx="100" cy="100" :r="radius" class="px-donut-chart__track" :stroke-width="safeThickness"></circle>
        <circle
          v-for="segment in segments"
          :key="segment.item.key"
          cx="100"
          cy="100"
          :r="radius"
          class="px-donut-chart__segment"
          :class="{ 'is-selected': selectedKey === segment.item.key, 'is-disabled': segment.item.disabled }"
          fill="none"
          :stroke="segment.color"
          :stroke-width="safeThickness"
          :stroke-dasharray="`${segment.dash} ${segment.gap}`"
          :stroke-dashoffset="segment.offset"
          :role="segment.item.disabled ? undefined : 'button'"
          :tabindex="segment.item.disabled ? undefined : 0"
          :aria-label="`${segment.item.label}：${format(segment.value)}，占比 ${segment.percent.toFixed(1)}%`"
          :aria-pressed="segment.item.disabled ? undefined : selectedKey === segment.item.key"
          @click="select(segment)"
          @keydown.enter.prevent="select(segment)"
          @keydown.space.prevent="select(segment)"
        ></circle>
      </svg>
      <div class="px-donut-chart__center" aria-hidden="true"><span>{{ centerLabel }}</span><strong>{{ format(total) }}</strong></div>
      <ul class="px-donut-chart__labels" aria-label="占比明细">
        <li v-for="segment in segments" :key="segment.item.key">
          <span :style="{ backgroundColor: segment.color }" aria-hidden="true"></span>{{ segment.item.label }}
          <strong>{{ segment.percent.toFixed(1) }}%</strong>
        </li>
      </ul>
    </div>
  </section>
</template>
