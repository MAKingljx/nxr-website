<script setup lang="ts">
import { computed } from 'vue'

export type PhoenixQueryValue = string | number | boolean | null
export interface PhoenixQueryOption { label: string; value: string | number; disabled?: boolean }
export interface PhoenixQueryField {
  key: string
  label: string
  type?: 'text' | 'search' | 'number' | 'date' | 'select'
  placeholder?: string
  options?: PhoenixQueryOption[]
  disabled?: boolean
}
const props = withDefaults(defineProps<{
  fields?: PhoenixQueryField[]
  modelValue?: Record<string, PhoenixQueryValue>
  collapsed?: boolean
  visibleCount?: number
  loading?: boolean
  title?: string
}>(), {
  fields: () => [], modelValue: () => ({}), collapsed: false, visibleCount: 4, loading: false, title: '查询条件',
})
const emit = defineEmits<{
  'update:modelValue': [value: Record<string, PhoenixQueryValue>]
  'update:collapsed': [value: boolean]
  query: [value: Record<string, PhoenixQueryValue>]
  reset: []
}>()
const limit = computed(() => Math.max(1, Math.min(12, Math.floor(Number.isFinite(props.visibleCount) ? props.visibleCount : 4))))
const visibleFields = computed(() => props.collapsed ? props.fields.slice(0, limit.value) : props.fields)
function update(field: PhoenixQueryField, raw: PhoenixQueryValue) {
  let value: PhoenixQueryValue = raw
  if (field.type === 'number') {
    const parsed = Number(raw)
    value = raw === '' || !Number.isFinite(parsed) ? null : parsed
  }
  emit('update:modelValue', { ...props.modelValue, [field.key]: value })
}
function updateSelect(field: PhoenixQueryField, raw: string) {
  const option = field.options?.find((item) => String(item.value) === raw)
  update(field, raw === '' ? null : option?.value ?? raw)
}
</script>

<template>
  <form class="px-query-panel" :aria-label="title" @submit.prevent="emit('query', modelValue)">
    <div class="px-query-panel__fields">
      <label v-for="field in visibleFields" :key="field.key">
        <span>{{ field.label }}</span>
        <select v-if="field.type === 'select'" :value="modelValue[field.key] ?? ''" :disabled="field.disabled || loading" @change="updateSelect(field, ($event.target as HTMLSelectElement).value)">
          <option value="">全部</option>
          <option v-for="option in field.options || []" :key="String(option.value)" :value="option.value" :disabled="option.disabled">{{ option.label }}</option>
        </select>
        <input v-else :type="field.type || 'text'" :value="modelValue[field.key] ?? ''" :placeholder="field.placeholder || `请输入${field.label}`" :disabled="field.disabled || loading" @input="update(field, ($event.target as HTMLInputElement).value)">
      </label>
    </div>
    <footer>
      <button v-if="fields.length > limit" type="button" class="is-quiet" :aria-expanded="!collapsed" @click="emit('update:collapsed', !collapsed)">{{ collapsed ? '展开条件' : '收起条件' }}</button>
      <span></span>
      <button type="button" class="is-quiet" :disabled="loading" @click="emit('reset')">重置</button>
      <button type="submit" :disabled="loading">{{ loading ? '查询中' : '查询' }}</button>
    </footer>
  </form>
</template>
