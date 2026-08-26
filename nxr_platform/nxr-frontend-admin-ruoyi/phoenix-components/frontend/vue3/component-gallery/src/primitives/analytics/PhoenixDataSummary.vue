<script setup lang="ts">
import { computed } from 'vue'
import type { PhoenixAnalyticsAppearance, PhoenixAnalyticsValueKind } from './types'
import { formatAnalyticsValue, safeColor, safePositive, safeSigned, uniqueByKey } from './utils'

export interface PhoenixDataSummaryItem {
  key: string
  label: string
  value: number
  kind?: PhoenixAnalyticsValueKind
  currency?: string
  unit?: string
  note?: string
  color?: string
  positiveOnly?: boolean
  disabled?: boolean
}

const props = withDefaults(defineProps<{
  items: PhoenixDataSummaryItem[]
  title?: string
  selectedKey?: string
  locale?: string
  currency?: string
  appearance?: PhoenixAnalyticsAppearance
  emptyText?: string
}>(), {
  title: '数据摘要',
  selectedKey: '',
  locale: 'zh-CN',
  currency: 'CNY',
  appearance: 'modern',
  emptyText: '暂无摘要数据',
})

const emit = defineEmits<{
  'update:selectedKey': [key: string]
  select: [item: PhoenixDataSummaryItem]
}>()

const normalizedItems = computed(() => uniqueByKey(props.items))

function itemValue(item: PhoenixDataSummaryItem) {
  const value = item.positiveOnly ? safePositive(item.value) : safeSigned(item.value)
  return formatAnalyticsValue(value, item.kind, props.locale, item.currency || props.currency)
}

function select(item: PhoenixDataSummaryItem) {
  if (item.disabled) return
  emit('update:selectedKey', item.key)
  emit('select', item)
}
</script>

<template>
  <section class="px-data-summary" :class="`is-${appearance}`" :aria-label="title">
    <h3>{{ title }}</h3>
    <p v-if="!normalizedItems.length" class="px-analytics-empty" role="status">{{ emptyText }}</p>
    <ul v-else class="px-data-summary__grid" role="list">
      <li v-for="(item, index) in normalizedItems" :key="item.key">
        <button
          type="button"
          :disabled="item.disabled"
          :aria-pressed="selectedKey === item.key"
          :class="{ 'is-selected': selectedKey === item.key }"
          @click="select(item)"
        >
          <span class="px-data-summary__marker" :style="{ backgroundColor: safeColor(item.color, index) }" aria-hidden="true"></span>
          <span class="px-data-summary__label">{{ item.label }}</span>
          <strong>{{ itemValue(item) }}<small v-if="item.unit">{{ item.unit }}</small></strong>
          <span v-if="item.note" class="px-data-summary__note">{{ item.note }}</span>
        </button>
      </li>
    </ul>
  </section>
</template>
