<script setup lang="ts">
import { computed, useId } from 'vue'
import type { PhoenixAnalyticsAppearance, PhoenixAnalyticsValueKind, PhoenixChartDatum } from './types'
import { formatAnalyticsValue, safeColor, safeDimension, safeSigned, svgPolylinePoints, uniqueByKey } from './utils'

const props = withDefaults(defineProps<{
  data: PhoenixChartDatum[]
  title?: string
  ariaLabel?: string
  height?: number
  activeKey?: string
  valueKind?: PhoenixAnalyticsValueKind
  locale?: string
  currency?: string
  showArea?: boolean
  appearance?: PhoenixAnalyticsAppearance
  emptyText?: string
}>(), {
  title: '趋势分析',
  height: 220,
  activeKey: '',
  valueKind: 'number',
  locale: 'zh-CN',
  currency: 'CNY',
  showArea: true,
  appearance: 'modern',
  emptyText: '暂无趋势数据',
})

const emit = defineEmits<{
  'update:activeKey': [key: string]
  select: [item: PhoenixChartDatum, index: number]
}>()

const width = 720
const padding = 34
const gradientId = `px-trend-${useId().replace(/[^a-z\d-_]/gi, '')}`
const safeHeight = computed(() => safeDimension(props.height, 220, 140, 480))
const normalizedData = computed(() => uniqueByKey(props.data).map((item) => ({ item, value: safeSigned(item.value) })))
const points = computed(() => svgPolylinePoints(normalizedData.value.map(({ value }) => value), width, safeHeight.value, padding))
const linePath = computed(() => points.value.map((point, index) => `${index ? 'L' : 'M'} ${point.x} ${point.y}`).join(' '))
const areaPath = computed(() => {
  if (!points.value.length) return ''
  return `${linePath.value} L ${points.value.at(-1)?.x ?? padding} ${safeHeight.value - padding} L ${points.value[0].x} ${safeHeight.value - padding} Z`
})
const description = computed(() => normalizedData.value.map(({ item, value }) => `${item.label} ${format(value)}`).join('，'))

function format(value: number) {
  return formatAnalyticsValue(value, props.valueKind, props.locale, props.currency)
}

function select(item: PhoenixChartDatum, index: number) {
  if (item.disabled) return
  emit('update:activeKey', item.key)
  emit('select', item, index)
}
</script>

<template>
  <section class="px-trend-chart" :class="`is-${appearance}`" :aria-label="title">
    <header class="px-analytics-header"><h3>{{ title }}</h3><slot name="actions"></slot></header>
    <p v-if="!normalizedData.length" class="px-analytics-empty" role="status">{{ emptyText }}</p>
    <svg v-else class="px-trend-chart__svg" :viewBox="`0 0 ${width} ${safeHeight}`" role="group" :aria-label="ariaLabel || title">
      <title>{{ ariaLabel || title }}</title>
      <desc>{{ description }}</desc>
      <defs>
        <linearGradient :id="gradientId" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stop-color="#5b5ce2" stop-opacity="0.28"></stop>
          <stop offset="1" stop-color="#5b5ce2" stop-opacity="0"></stop>
        </linearGradient>
      </defs>
      <g class="px-chart-grid" aria-hidden="true">
        <line v-for="line in 4" :key="line" :x1="padding" :x2="width - padding" :y1="padding + ((line - 1) * (safeHeight - padding * 2)) / 3" :y2="padding + ((line - 1) * (safeHeight - padding * 2)) / 3"></line>
      </g>
      <path v-if="showArea" class="px-trend-chart__area" :d="areaPath" :fill="`url(#${gradientId})`" aria-hidden="true"></path>
      <path class="px-trend-chart__line" :d="linePath" aria-hidden="true"></path>
      <g
        v-for="({ item, value }, index) in normalizedData"
        :key="item.key"
        class="px-trend-chart__point"
        :class="{ 'is-active': activeKey === item.key, 'is-disabled': item.disabled }"
        :transform="`translate(${points[index].x} ${points[index].y})`"
        :role="item.disabled ? undefined : 'button'"
        :tabindex="item.disabled ? undefined : 0"
        :aria-label="`${item.label}：${format(value)}`"
        :aria-pressed="item.disabled ? undefined : activeKey === item.key"
        @click="select(item, index)"
        @keydown.enter.prevent="select(item, index)"
        @keydown.space.prevent="select(item, index)"
      >
        <circle r="12" class="px-trend-chart__hit"></circle>
        <circle r="5" :fill="safeColor(item.color, index)"></circle>
      </g>
    </svg>
  </section>
</template>
