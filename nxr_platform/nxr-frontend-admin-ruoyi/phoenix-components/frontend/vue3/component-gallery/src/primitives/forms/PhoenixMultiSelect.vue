<script setup lang="ts">
import { computed, useId } from 'vue'

export type PhoenixMultiSelectValue = string | number
export interface PhoenixMultiSelectOption { label: string; value: PhoenixMultiSelectValue; disabled?: boolean }

const props = withDefaults(defineProps<{
  modelValue?: PhoenixMultiSelectValue[]
  options?: PhoenixMultiSelectOption[]
  id?: string
  name?: string
  label?: string
  size?: number
  disabled?: boolean
  required?: boolean
  invalid?: boolean
}>(), {
  modelValue: () => [], options: () => [], id: undefined, name: undefined, label: '多选项', size: 5,
  disabled: false, required: false, invalid: false,
})
const emit = defineEmits<{
  'update:modelValue': [value: PhoenixMultiSelectValue[]]
  change: [value: PhoenixMultiSelectValue[]]
}>()
const uid = useId()
const fieldName = computed(() => props.name || `phoenix-multi-select-${uid}`)
const safeSize = computed(() => Math.max(2, Math.min(12, Math.trunc(props.size) || 5)))

function read(event: Event) {
  const selected = Array.from((event.target as HTMLSelectElement).selectedOptions).map((item) => {
    const index = Number(item.dataset.index)
    return props.options[index]?.value
  }).filter((value): value is PhoenixMultiSelectValue => value !== undefined)
  emit('update:modelValue', selected)
  emit('change', selected)
}
</script>

<template>
  <div class="px-multi-select" :class="{ 'is-disabled': disabled, 'is-invalid': invalid }">
    <select
      :id="id" multiple :name="`${fieldName}[]`" :size="safeSize" :aria-label="label"
      :aria-invalid="invalid || undefined" :disabled="disabled" :required="required" @change="read"
    >
      <option
        v-for="(option, index) in options" :key="`${String(option.value)}-${index}`"
        :value="String(option.value)" :data-index="index" :selected="modelValue.includes(option.value)"
        :disabled="option.disabled"
      >
        {{ option.label }}
      </option>
    </select>
  </div>
</template>
