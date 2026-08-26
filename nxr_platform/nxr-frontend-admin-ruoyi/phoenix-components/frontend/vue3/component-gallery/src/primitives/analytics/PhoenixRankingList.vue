<script setup lang="ts">
import { computed } from 'vue'
import type { PhoenixAnalyticsAppearance, PhoenixAnalyticsValueKind } from './types'
import { formatAnalyticsValue, safeColor, safeDimension, safePositive, safeSigned, uniqueByKey } from './utils'

export interface PhoenixRankingItem {
  key: string
  label: string
  value: number
  unit?: string
  color?: string
  description?: string
  trend?: number
  disabled?: boolean
}

const props = withDefaults(defineProps<{
  items: PhoenixRankingItem[]
  title?: string
  selectedKey?: string
  valueKind?: PhoenixAnalyticsValueKind
  locale?: string
  currency?: string
  limit?: number
  sorted?: boolean
  appearance?: PhoenixAnalyticsAppearance
  emptyText?: string
}>(), {
  title: '数据排行',
  selectedKey: '',
  valueKind: 'number',
  locale: 'zh-CN',
  currency: 'CNY',
  limit: 10,
  sorted: true,
  appearance: 'modern',
  emptyText: '暂无排行数据',
})

const emit = defineEmits<{
  'update:selectedKey': [key: string]
  select: [item: PhoenixRankingItem, rank: number]
}>()

const normalizedItems = computed(() => {
  const source = uniqueByKey(props.items).map((item, index) => ({ item, value: safePositive(item.value), sourceIndex: index }))
  if (props.sorted) source.sort((a, b) => b.value - a.value || a.sourceIndex - b.sourceIndex)
  return source.slice(0, Math.round(safeDimension(props.limit, 10, 1, 100)))
})
const maximum = computed(() => Math.max(1, ...normalizedItems.value.map(({ value }) => value)))

function format(value: number) {
  return formatAnalyticsValue(value, props.valueKind, props.locale, props.currency)
}

function trend(value: number | undefined) {
  return Math.min(999, Math.max(-999, safeSigned(value ?? 0)))
}

function select(item: PhoenixRankingItem, rank: number) {
  if (item.disabled) return
  emit('update:selectedKey', item.key)
  emit('select', item, rank)
}
</script>

<template>
  <section class="px-ranking-list" :class="`is-${appearance}`" :aria-label="title">
    <header class="px-analytics-header"><h3>{{ title }}</h3><slot name="actions"></slot></header>
    <p v-if="!normalizedItems.length" class="px-analytics-empty" role="status">{{ emptyText }}</p>
    <ol v-else class="px-ranking-list__items">
      <li v-for="({ item, value }, index) in normalizedItems" :key="item.key">
        <button
          type="button"
          :disabled="item.disabled"
          :aria-label="`第 ${index + 1} 名，${item.label}：${format(value)}${item.unit || ''}`"
          :aria-pressed="selectedKey === item.key"
          :class="{ 'is-selected': selectedKey === item.key }"
          @click="select(item, index + 1)"
        >
          <span class="px-ranking-list__rank" :class="`is-${Math.min(4, index + 1)}`">{{ index + 1 }}</span>
          <span class="px-ranking-list__main">
            <span class="px-ranking-list__heading"><strong>{{ item.label }}</strong><small v-if="item.description">{{ item.description }}</small></span>
            <span class="px-ranking-list__track" aria-hidden="true"><span :style="{ width: `${(value / maximum) * 100}%`, backgroundColor: safeColor(item.color, index) }"></span></span>
          </span>
          <span class="px-ranking-list__value"><strong>{{ format(value) }}<small v-if="item.unit">{{ item.unit }}</small></strong><em v-if="item.trend !== undefined" :class="trend(item.trend) >= 0 ? 'is-up' : 'is-down'">{{ trend(item.trend) >= 0 ? '↑' : '↓' }} {{ Math.abs(trend(item.trend)).toFixed(1) }}%</em></span>
        </button>
      </li>
    </ol>
  </section>
</template>
