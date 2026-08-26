<script setup lang="ts">
withDefaults(
  defineProps<{
    modelValue: boolean
    label?: string
    activeText?: string
    inactiveText?: string
    disabled?: boolean
    size?: 'small' | 'medium'
  }>(),
  {
    label: '开关',
    activeText: '',
    inactiveText: '',
    disabled: false,
    size: 'medium',
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  change: [value: boolean]
}>()

function toggle(value: boolean, disabled: boolean) {
  if (disabled) return
  emit('update:modelValue', !value)
  emit('change', !value)
}
</script>

<template>
  <label class="px-switch" :class="[`px-switch--${size}`, { 'is-disabled': disabled }]">
    <button
      type="button"
      role="switch"
      class="px-switch__control"
      :aria-label="label"
      :aria-checked="modelValue"
      :disabled="disabled"
      @click="toggle(modelValue, disabled)"
    ><span aria-hidden="true"></span></button>
    <span v-if="activeText || inactiveText" class="px-switch__text">{{ modelValue ? activeText : inactiveText }}</span>
  </label>
</template>
