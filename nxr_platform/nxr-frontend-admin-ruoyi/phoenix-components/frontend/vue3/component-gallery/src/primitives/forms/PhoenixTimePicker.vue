<script setup lang="ts">
import { computed, useId } from 'vue'

const props = withDefaults(defineProps<{
  modelValue?: string
  id?: string
  name?: string
  label?: string
  min?: string
  max?: string
  step?: number
  disabled?: boolean
  readonly?: boolean
  required?: boolean
  invalid?: boolean
}>(), {
  modelValue: '', id: undefined, name: undefined, label: '选择时间', min: undefined, max: undefined,
  step: 60, disabled: false, readonly: false, required: false, invalid: false,
})
const emit = defineEmits<{ 'update:modelValue': [value: string]; change: [value: string] }>()
const uid = useId()
const fieldName = computed(() => props.name || `phoenix-time-picker-${uid}`)
const safeStep = computed(() => Math.max(1, Math.min(86400, Math.trunc(Number(props.step) || 60))))
function normalize(value: string) {
  if (value === '') return ''
  const match = /^(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value)
  if (!match) return ''
  const hour = Number(match[1]); const minute = Number(match[2]); const second = Number(match[3] || 0)
  if (hour > 23 || minute > 59 || second > 59) return ''
  return match[3] ? `${match[1]}:${match[2]}:${match[3]}` : `${match[1]}:${match[2]}`
}
function read(event: Event, commit = false) {
  const value = normalize((event.target as HTMLInputElement).value)
  emit('update:modelValue', value)
  if (commit) emit('change', value)
}
</script>

<template>
  <div class="px-time-picker" :class="{ 'is-disabled': disabled, 'is-invalid': invalid }">
    <input
      :id="id" type="time" :name="fieldName" :value="modelValue" :aria-label="label"
      :aria-invalid="invalid || undefined" :min="min" :max="max" :step="safeStep"
      :disabled="disabled" :readonly="readonly" :required="required"
      @input="read($event)" @change="read($event, true)"
    >
  </div>
</template>
