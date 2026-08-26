<script setup lang="ts">
import { computed } from 'vue'
import type { PhoenixAnalyticsAppearance, PhoenixAnalyticsValueKind, PhoenixChartDatum } from './types'
import { formatAnalyticsValue, safeColor, safePositive, uniqueByKey } from './utils'

const props = withDefaults(defineProps<{
  stages: PhoenixChartDatum[]
  title?: string
  activeKey?: string
  valueKind?: PhoenixAnalyticsValueKind
  locale?: string
  currency?: string
  appearance?: PhoenixAnalyticsAppearance
  emptyText?: string
}>(), {
  title: '转化漏斗',
  activeKey: '',
  valueKind: 'number',
  locale: 'zh-CN',
  currency: 'CNY',
  appearance: 'modern',
  emptyText: '暂无漏斗数据',
})

const emit = defineEmits<{
  'update:activeKey': [key: string]
  select: [stage: PhoenixChartDatum, conversion: number]
}>()

const normalizedStages = computed(() => uniqueByKey(props.stages).map((stage, index) => ({ stage, value: safePositive(stage.value), color: safeColor(stage.color, index) })))
const maximum = computed(() => Math.max(1, ...normalizedStages.value.map(({ value }) => value)))
const firstValue = computed(() => normalizedStages.value[0]?.value ?? 0)

function conversion(value: number) {
  return firstValue.value ? Math.min(100, (value / firstValue.value) * 100) : 0
}

function format(value: number) {
  return formatAnalyticsValue(value, props.valueKind, props.locale, props.currency)
}

function select(stage: PhoenixChartDatum, value: number) {
  if (stage.disabled) return
  emit('update:activeKey', stage.key)
  emit('select', stage, conversion(value))
}
</script>

<template>
  <section class="px-funnel-chart" :class="`is-${appearance}`" :aria-label="title">
    <header class="px-analytics-header"><h3>{{ title }}</h3><slot name="actions"></slot></header>
    <p v-if="!normalizedStages.length" class="px-analytics-empty" role="status">{{ emptyText }}</p>
    <ol v-else class="px-funnel-chart__list">
      <li v-for="({ stage, value, color }, index) in normalizedStages" :key="stage.key">
        <button
          type="button"
          :disabled="stage.disabled"
          :aria-label="`${stage.label}：${format(value)}，总体转化率 ${conversion(value).toFixed(1)}%`"
          :aria-pressed="activeKey === stage.key"
          :class="{ 'is-active': activeKey === stage.key }"
          :style="{ width: `${Math.max(16, (value / maximum) * 100)}%`, backgroundColor: color }"
          @click="select(stage, value)"
        >
          <span><small>第 {{ index + 1 }} 步</small>{{ stage.label }}</span>
          <strong>{{ format(value) }}</strong>
          <em>{{ conversion(value).toFixed(1) }}%</em>
        </button>
      </li>
    </ol>
  </section>
</template>
