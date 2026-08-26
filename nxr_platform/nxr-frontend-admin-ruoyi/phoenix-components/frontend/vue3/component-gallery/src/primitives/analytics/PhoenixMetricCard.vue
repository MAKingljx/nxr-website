<script setup lang="ts">
import { computed } from 'vue'
import type { PhoenixAnalyticsAppearance, PhoenixAnalyticsValueKind } from './types'
import { formatAnalyticsValue, safeSigned } from './utils'

const props = withDefaults(defineProps<{
  label?: string
  value?: number
  kind?: PhoenixAnalyticsValueKind
  currency?: string
  locale?: string
  unit?: string
  description?: string
  trend?: number
  trendLabel?: string
  appearance?: PhoenixAnalyticsAppearance
  interactive?: boolean
  selected?: boolean
  disabled?: boolean
}>(), {
  label: '核心指标',
  value: 0,
  kind: 'number',
  currency: 'CNY',
  locale: 'zh-CN',
  unit: '',
  description: '',
  trendLabel: '较上期',
  appearance: 'modern',
  interactive: false,
  selected: false,
  disabled: false,
})

const emit = defineEmits<{
  select: [value: number]
}>()

const safeValue = computed(() => safeSigned(props.value))
const formattedValue = computed(() => formatAnalyticsValue(safeValue.value, props.kind, props.locale, props.currency))
const safeTrend = computed(() => Math.min(999, Math.max(-999, safeSigned(props.trend ?? 0))))
const ariaText = computed(() => `${props.label}：${formattedValue.value}${props.unit}${props.trend === undefined ? '' : `，${props.trendLabel}${safeTrend.value >= 0 ? '上升' : '下降'} ${Math.abs(safeTrend.value).toFixed(1)}%`}`)

function select() {
  if (!props.disabled) emit('select', safeValue.value)
}
</script>

<template>
  <article class="px-metric-card" :class="[`is-${appearance}`, { 'is-selected': selected, 'is-disabled': disabled }]" :aria-label="ariaText">
    <component
      :is="interactive ? 'button' : 'div'"
      class="px-metric-card__body"
      :type="interactive ? 'button' : undefined"
      :disabled="interactive ? disabled : undefined"
      :aria-pressed="interactive ? selected : undefined"
      @click="interactive && select()"
    >
      <span class="px-metric-card__heading">
        <span class="px-metric-card__label">{{ label }}</span>
        <span v-if="$slots.icon" class="px-metric-card__icon" aria-hidden="true"><slot name="icon"></slot></span>
      </span>
      <strong class="px-metric-card__value">{{ formattedValue }}<small v-if="unit">{{ unit }}</small></strong>
      <span v-if="trend !== undefined" class="px-metric-card__trend" :class="safeTrend >= 0 ? 'is-up' : 'is-down'">
        <span aria-hidden="true">{{ safeTrend >= 0 ? '↗' : '↘' }}</span>
        {{ trendLabel }} {{ Math.abs(safeTrend).toFixed(1) }}%
      </span>
      <span v-if="description" class="px-metric-card__description">{{ description }}</span>
    </component>
  </article>
</template>
