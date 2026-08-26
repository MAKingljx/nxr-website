<script setup lang="ts">
import { computed } from 'vue'
import type { PhoenixAnalyticsAppearance, PhoenixAnalyticsValueKind, PhoenixChartDatum } from './types'
import { formatAnalyticsValue, safeColor, safePositive, uniqueByKey } from './utils'

const props = withDefaults(defineProps<{
  data: PhoenixChartDatum[]
  title?: string
  activeKey?: string
  valueKind?: PhoenixAnalyticsValueKind
  locale?: string
  currency?: string
  appearance?: PhoenixAnalyticsAppearance
  showValues?: boolean
  emptyText?: string
}>(), {
  title: '分类对比',
  activeKey: '',
  valueKind: 'number',
  locale: 'zh-CN',
  currency: 'CNY',
  appearance: 'modern',
  showValues: true,
  emptyText: '暂无柱状图数据',
})

const emit = defineEmits<{
  'update:activeKey': [key: string]
  select: [item: PhoenixChartDatum, index: number]
}>()

const normalizedData = computed(() => uniqueByKey(props.data).map((item) => ({ item, value: safePositive(item.value) })))
const maximum = computed(() => Math.max(1, ...normalizedData.value.map(({ value }) => value)))

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
  <section class="px-bar-chart" :class="`is-${appearance}`" :aria-label="title">
    <header class="px-analytics-header"><h3>{{ title }}</h3><slot name="actions"></slot></header>
    <p v-if="!normalizedData.length" class="px-analytics-empty" role="status">{{ emptyText }}</p>
    <div v-else class="px-bar-chart__plot" role="list" :aria-label="`${title}数据`">
      <div
        v-for="({ item, value }, index) in normalizedData"
        :key="item.key"
        role="listitem"
      >
        <button
          type="button"
          :disabled="item.disabled"
          :aria-label="`${item.label}：${format(value)}`"
          :aria-pressed="activeKey === item.key"
          :class="{ 'is-active': activeKey === item.key }"
          @click="select(item, index)"
        >
          <span class="px-bar-chart__label">{{ item.label }}</span>
          <span class="px-bar-chart__track" aria-hidden="true">
            <span class="px-bar-chart__bar" :style="{ width: `${(value / maximum) * 100}%`, backgroundColor: safeColor(item.color, index) }"></span>
          </span>
          <strong v-if="showValues">{{ format(value) }}</strong>
        </button>
      </div>
    </div>
  </section>
</template>
