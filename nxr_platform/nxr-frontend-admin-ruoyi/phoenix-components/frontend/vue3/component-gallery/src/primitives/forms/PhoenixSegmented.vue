<script setup lang="ts">
import { computed, useId } from 'vue'

export type PhoenixSegmentedValue = string | number
export interface PhoenixSegmentedOption { label: string; value: PhoenixSegmentedValue; disabled?: boolean }
const props = withDefaults(defineProps<{
  modelValue: PhoenixSegmentedValue | null
  options?: PhoenixSegmentedOption[]
  name?: string
  label?: string
  disabled?: boolean
  fullWidth?: boolean
}>(), {
  options: () => [], name: undefined, label: '分段选择', disabled: false, fullWidth: false,
})
const emit = defineEmits<{ 'update:modelValue': [value: PhoenixSegmentedValue]; change: [value: PhoenixSegmentedValue] }>()
const uid = useId()
const fieldName = computed(() => props.name || `phoenix-segmented-${uid}`)
function select(value: PhoenixSegmentedValue) {
  emit('update:modelValue', value)
  emit('change', value)
}
</script>

<template>
  <fieldset class="px-segmented" :class="{ 'is-full': fullWidth }" :disabled="disabled">
    <legend class="px-sr-only">{{ label }}</legend>
    <label v-for="option in options" :key="String(option.value)" :class="{ 'is-active': modelValue === option.value, 'is-disabled': disabled || option.disabled }">
      <input
        type="radio" :name="fieldName" :value="String(option.value)" :checked="modelValue === option.value"
        :disabled="disabled || option.disabled" @change="select(option.value)"
      >
      <span>{{ option.label }}</span>
    </label>
  </fieldset>
</template>
