<script setup lang="ts">
import { computed } from 'vue'
import type { PhoenixAnalyticsAppearance, PhoenixLegendItem } from './types'
import { safeColor, safeSigned, uniqueByKey } from './utils'

const props = withDefaults(defineProps<{
  items: PhoenixLegendItem[]
  hiddenKeys?: string[]
  title?: string
  appearance?: PhoenixAnalyticsAppearance
  selectable?: boolean
  emptyText?: string
}>(), {
  hiddenKeys: () => [],
  title: '图表图例',
  appearance: 'modern',
  selectable: true,
  emptyText: '暂无图例',
})

const emit = defineEmits<{
  'update:hiddenKeys': [keys: string[]]
  toggle: [item: PhoenixLegendItem, visible: boolean]
}>()

const normalizedItems = computed(() => uniqueByKey(props.items))
const hidden = computed(() => new Set(props.hiddenKeys))

function toggle(item: PhoenixLegendItem) {
  if (!props.selectable || item.disabled) return
  const nextHidden = new Set(props.hiddenKeys)
  const willShow = nextHidden.has(item.key)
  if (willShow) nextHidden.delete(item.key)
  else nextHidden.add(item.key)
  emit('update:hiddenKeys', [...nextHidden])
  emit('toggle', item, willShow)
}

function value(item: PhoenixLegendItem) {
  return typeof item.value === 'number' ? safeSigned(item.value).toLocaleString('zh-CN') : item.value
}
</script>

<template>
  <div class="px-chart-legend" :class="`is-${appearance}`" role="group" :aria-label="title">
    <p v-if="!normalizedItems.length" class="px-analytics-empty" role="status">{{ emptyText }}</p>
    <button
      v-for="(item, index) in normalizedItems"
      v-else
      :key="item.key"
      type="button"
      :disabled="item.disabled || !selectable"
      :aria-pressed="!hidden.has(item.key)"
      :class="{ 'is-hidden': hidden.has(item.key) }"
      @click="toggle(item)"
    >
      <span class="px-chart-legend__marker" :style="{ backgroundColor: safeColor(item.color, index) }" aria-hidden="true"></span>
      <span>{{ item.label }}</span>
      <strong v-if="item.value !== undefined">{{ value(item) }}</strong>
    </button>
  </div>
</template>
