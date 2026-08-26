<script setup lang="ts">
import { computed, useId } from 'vue'

export type PhoenixDateRangeValue = [string, string]
const props = withDefaults(defineProps<{
  modelValue?: PhoenixDateRangeValue
  id?: string
  name?: string
  label?: string
  startLabel?: string
  endLabel?: string
  min?: string
  max?: string
  disabled?: boolean
  readonly?: boolean
  required?: boolean
  invalid?: boolean
}>(), {
  modelValue: () => ['', ''], id: undefined, name: undefined, label: '日期范围', startLabel: '开始日期',
  endLabel: '结束日期', min: undefined, max: undefined, disabled: false, readonly: false, required: false, invalid: false,
})
const emit = defineEmits<{ 'update:modelValue': [value: PhoenixDateRangeValue]; change: [value: PhoenixDateRangeValue] }>()
const uid = useId()
const baseId = computed(() => props.id || `phoenix-date-range-${uid}`)
const fieldName = computed(() => props.name || `phoenix-date-range-${uid}`)
function normalize(value: PhoenixDateRangeValue): PhoenixDateRangeValue {
  const [start, end] = value.map((item) => /^\d{4}-\d{2}-\d{2}$/.test(item) ? item : '') as PhoenixDateRangeValue
  return start && end && start > end ? [end, start] : [start, end]
}
function update(index: 0 | 1, event: Event, commit = false) {
  const next: PhoenixDateRangeValue = [...props.modelValue] as PhoenixDateRangeValue
  next[index] = (event.target as HTMLInputElement).value
  const value = normalize(next)
  emit('update:modelValue', value)
  if (commit) emit('change', value)
}
</script>

<template>
  <fieldset class="px-date-range" :class="{ 'is-disabled': disabled, 'is-invalid': invalid }" :disabled="disabled">
    <legend class="px-sr-only">{{ label }}</legend>
    <input
      :id="`${baseId}-start`" type="date" :name="`${fieldName}-start`" :value="modelValue[0]"
      :aria-label="startLabel" :aria-invalid="invalid || undefined" :min="min" :max="modelValue[1] || max"
      :readonly="readonly" :required="required" @input="update(0, $event)" @change="update(0, $event, true)"
    >
    <span aria-hidden="true">至</span>
    <input
      :id="`${baseId}-end`" type="date" :name="`${fieldName}-end`" :value="modelValue[1]"
      :aria-label="endLabel" :aria-invalid="invalid || undefined" :min="modelValue[0] || min" :max="max"
      :readonly="readonly" :required="required" @input="update(1, $event)" @change="update(1, $event, true)"
    >
  </fieldset>
</template>
