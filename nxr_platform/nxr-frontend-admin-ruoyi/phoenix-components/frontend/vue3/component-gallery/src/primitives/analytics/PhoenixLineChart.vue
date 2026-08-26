<script setup lang="ts">
import { computed } from 'vue'
import type { PhoenixAnalyticsAppearance, PhoenixAnalyticsValueKind, PhoenixLinePoint, PhoenixLineSeries } from './types'
import { chartRange, formatAnalyticsValue, safeColor, safeDimension, safeSigned, uniqueByKey } from './utils'

interface NormalizedLineSeries {
  series: PhoenixLineSeries
  points: Array<{ point: PhoenixLinePoint, value: number, x: number, y: number }>
  color: string
  path: string
}

const props = withDefaults(defineProps<{
  series: PhoenixLineSeries[]
  title?: string
  height?: number
  hiddenKeys?: string[]
  activePointKey?: string
  valueKind?: PhoenixAnalyticsValueKind
  locale?: string
  currency?: string
  appearance?: PhoenixAnalyticsAppearance
  emptyText?: string
}>(), {
  title: '多维趋势',
  height: 240,
  hiddenKeys: () => [],
  activePointKey: '',
  valueKind: 'number',
  locale: 'zh-CN',
  currency: 'CNY',
  appearance: 'modern',
  emptyText: '暂无折线图数据',
})

const emit = defineEmits<{
  'update:activePointKey': [key: string]
  select: [point: PhoenixLinePoint, series: PhoenixLineSeries]
}>()

const width = 720
const padding = 36
const safeHeight = computed(() => safeDimension(props.height, 240, 160, 520))
const sourceSeries = computed(() => uniqueByKey(props.series).map((series, seriesIndex) => ({
  series,
  color: safeColor(series.color, seriesIndex),
  points: uniqueByKey(series.points).map((point) => ({ point, value: safeSigned(point.value) })),
})))
const visibleSource = computed(() => sourceSeries.value.filter(({ series }) => !props.hiddenKeys.includes(series.key) && series.points.length))
const range = computed(() => chartRange(visibleSource.value.flatMap(({ points }) => points.map(({ value }) => value))))
const normalizedSeries = computed<NormalizedLineSeries[]>(() => visibleSource.value.map(({ series, color, points }) => {
  const coordinates = points.map(({ point, value }, index) => ({
    point,
    value,
    x: padding + (points.length === 1 ? (width - padding * 2) / 2 : (index / (points.length - 1)) * (width - padding * 2)),
    y: padding + ((range.value.maximum - value) / range.value.span) * (safeHeight.value - padding * 2),
  }))
  return {
    series,
    color,
    points: coordinates,
    path: coordinates.map(({ x, y }, index) => `${index ? 'L' : 'M'} ${x} ${y}`).join(' '),
  }
}))

const description = computed(() => normalizedSeries.value.map(({ series, points }) => `${series.label}：${points.map(({ point, value }) => `${point.label} ${format(value)}`).join('，')}`).join('；'))

function format(value: number) {
  return formatAnalyticsValue(value, props.valueKind, props.locale, props.currency)
}

function select(point: PhoenixLinePoint, series: PhoenixLineSeries) {
  emit('update:activePointKey', point.key)
  emit('select', point, series)
}
</script>

<template>
  <section class="px-line-chart" :class="`is-${appearance}`" :aria-label="title">
    <header class="px-analytics-header"><h3>{{ title }}</h3><slot name="actions"></slot></header>
    <p v-if="!normalizedSeries.length" class="px-analytics-empty" role="status">{{ emptyText }}</p>
    <svg v-else class="px-line-chart__svg" :viewBox="`0 0 ${width} ${safeHeight}`" role="group" :aria-label="title">
      <title>{{ title }}</title>
      <desc>{{ description }}</desc>
      <g class="px-chart-grid" aria-hidden="true">
        <line v-for="line in 5" :key="line" :x1="padding" :x2="width - padding" :y1="padding + ((line - 1) * (safeHeight - padding * 2)) / 4" :y2="padding + ((line - 1) * (safeHeight - padding * 2)) / 4"></line>
      </g>
      <g v-for="line in normalizedSeries" :key="line.series.key" class="px-line-chart__series">
        <path :d="line.path" :stroke="line.color" aria-hidden="true"></path>
        <g
          v-for="point in line.points"
          :key="point.point.key"
          class="px-line-chart__point"
          :class="{ 'is-active': activePointKey === point.point.key }"
          :transform="`translate(${point.x} ${point.y})`"
          role="button"
          tabindex="0"
          :aria-label="`${line.series.label}，${point.point.label}：${format(point.value)}`"
          :aria-pressed="activePointKey === point.point.key"
          @click="select(point.point, line.series)"
          @keydown.enter.prevent="select(point.point, line.series)"
          @keydown.space.prevent="select(point.point, line.series)"
        >
          <circle r="11" class="px-line-chart__hit"></circle>
          <circle r="4.5" :fill="line.color"></circle>
        </g>
      </g>
    </svg>
  </section>
</template>
