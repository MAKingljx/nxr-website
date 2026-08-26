<script setup lang="ts">
import { computed, useId } from 'vue'

export interface PhoenixRadioOption {
  label: string
  value: string | number
  disabled?: boolean
}

const props = withDefaults(
  defineProps<{
    modelValue: string | number | null
    options: PhoenixRadioOption[]
    label?: string
    name?: string
    disabled?: boolean
    direction?: 'horizontal' | 'vertical'
  }>(),
  {
    label: '单选项',
    name: undefined,
    disabled: false,
    direction: 'horizontal',
  },
)

const uid = useId()
const groupName = computed(() => props.name || `phoenix-radio-group-${uid}`)

const emit = defineEmits<{
  'update:modelValue': [value: string | number]
  change: [value: string | number]
}>()

function select(value: string | number) {
  emit('update:modelValue', value)
  emit('change', value)
}
</script>

<template>
  <fieldset class="px-radio-group" :class="`px-radio-group--${direction}`" :disabled="disabled">
    <legend class="px-sr-only">{{ label }}</legend>
    <label
      v-for="option in options"
      :key="option.value"
      class="px-radio"
      :class="{ 'is-disabled': disabled || option.disabled }"
    >
      <input
        type="radio"
        :name="groupName"
        :value="option.value"
        :checked="modelValue === option.value"
        :disabled="disabled || option.disabled"
        @change="select(option.value)"
      >
      <span class="px-radio__control" aria-hidden="true"></span>
      <span>{{ option.label }}</span>
    </label>
  </fieldset>
</template>
