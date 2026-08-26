<script setup lang="ts">
import { computed } from 'vue'
import type {
  PhoenixAnalyticsAppearance,
  PhoenixDashboardFilterItem,
  PhoenixDashboardFilterValue,
} from './types'
import { uniqueByKey } from './utils'

const props = withDefaults(defineProps<{
  filters: PhoenixDashboardFilterItem[]
  modelValue?: PhoenixDashboardFilterValue
  title?: string
  appearance?: PhoenixAnalyticsAppearance
  disabled?: boolean
  showSubmit?: boolean
  submitText?: string
  clearText?: string
}>(), {
  modelValue: () => ({}),
  title: '数据筛选',
  appearance: 'modern',
  disabled: false,
  showSubmit: true,
  submitText: '应用筛选',
  clearText: '清空',
})

const emit = defineEmits<{
  'update:modelValue': [value: PhoenixDashboardFilterValue]
  change: [key: string, value: string, nextValue: PhoenixDashboardFilterValue]
  clear: []
  submit: [value: PhoenixDashboardFilterValue]
}>()

const normalizedFilters = computed(() => uniqueByKey(props.filters))
const hasValue = computed(() => Object.values(props.modelValue).some(Boolean))

function options(filter: PhoenixDashboardFilterItem) {
  const seen = new Set<string>()
  return filter.options.filter((option) => !seen.has(option.value) && Boolean(seen.add(option.value)))
}

function update(key: string, event: Event) {
  const value = (event.target as HTMLSelectElement).value
  const nextValue = { ...props.modelValue, [key]: value }
  emit('update:modelValue', nextValue)
  emit('change', key, value, nextValue)
}

function clear() {
  emit('update:modelValue', {})
  emit('clear')
}

function submit() {
  emit('submit', { ...props.modelValue })
}
</script>

<template>
  <form class="px-dashboard-filter" :class="`is-${appearance}`" :aria-label="title" @submit.prevent="submit">
    <div class="px-dashboard-filter__fields">
      <label v-for="filter in normalizedFilters" :key="filter.key">
        <span>{{ filter.label }}</span>
        <select
          :value="modelValue[filter.key] || ''"
          :disabled="disabled || filter.disabled"
          :aria-label="filter.label"
          @change="update(filter.key, $event)"
        >
          <option value="">{{ filter.placeholder || `全部${filter.label}` }}</option>
          <option v-for="option in options(filter)" :key="option.value" :value="option.value" :disabled="option.disabled">
            {{ option.label }}
          </option>
        </select>
      </label>
    </div>
    <div class="px-dashboard-filter__actions">
      <button type="button" class="is-quiet" :disabled="disabled || !hasValue" @click="clear">{{ clearText }}</button>
      <button v-if="showSubmit" type="submit" class="is-primary" :disabled="disabled">{{ submitText }}</button>
    </div>
  </form>
</template>
