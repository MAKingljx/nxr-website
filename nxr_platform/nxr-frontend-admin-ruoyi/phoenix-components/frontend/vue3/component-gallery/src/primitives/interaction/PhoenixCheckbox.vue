<script setup lang="ts">
withDefaults(
  defineProps<{
    modelValue: boolean
    label?: string
    disabled?: boolean
    indeterminate?: boolean
    name?: string
  }>(),
  {
    label: '复选项',
    disabled: false,
    indeterminate: false,
    name: undefined,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  change: [value: boolean]
}>()

function update(event: Event) {
  const value = (event.target as HTMLInputElement).checked
  emit('update:modelValue', value)
  emit('change', value)
}
</script>

<template>
  <label class="px-checkbox" :class="{ 'is-disabled': disabled }">
    <input
      class="px-checkbox__input"
      type="checkbox"
      :name="name"
      :checked="modelValue"
      :indeterminate="indeterminate"
      :disabled="disabled"
      :aria-checked="indeterminate ? 'mixed' : modelValue"
      @change="update"
    >
    <span class="px-checkbox__control" aria-hidden="true">
      <span>{{ indeterminate ? '−' : '✓' }}</span>
    </span>
    <span class="px-checkbox__label"><slot>{{ label }}</slot></span>
  </label>
</template>
