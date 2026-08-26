<script setup lang="ts">
import { computed, useId } from 'vue'

const props = withDefaults(defineProps<{
  modelValue?: number
  id?: string
  name?: string
  label?: string
  max?: number
  disabled?: boolean
  readonly?: boolean
  clearable?: boolean
}>(), {
  modelValue: 0, id: undefined, name: undefined, label: '评分', max: 5, disabled: false, readonly: false, clearable: true,
})
const emit = defineEmits<{ 'update:modelValue': [value: number]; change: [value: number] }>()
const uid = useId()
const fieldName = computed(() => props.name || `phoenix-rate-${uid}`)
const safeMax = computed(() => Math.max(1, Math.min(10, Math.trunc(Number(props.max) || 5))))
const safeValue = computed(() => Math.max(0, Math.min(safeMax.value, Math.trunc(Number(props.modelValue) || 0))))
function select(value: number) {
  if (props.disabled || props.readonly) return
  const next = props.clearable && safeValue.value === value ? 0 : value
  emit('update:modelValue', next)
  emit('change', next)
}
</script>

<template>
  <fieldset class="px-rate" :class="{ 'is-disabled': disabled || readonly }" :disabled="disabled">
    <legend class="px-sr-only">{{ label }}</legend>
    <label v-for="value in safeMax" :key="value" :class="{ 'is-active': value <= safeValue }">
      <input
        :id="id ? `${id}-${value}` : undefined" type="radio" :name="fieldName" :value="value"
        :checked="value === safeValue" :disabled="disabled || readonly" :aria-label="`${value} 分`"
        @click.prevent="select(value)" @keydown.space.prevent="select(value)"
      >
      <span aria-hidden="true">★</span>
    </label>
    <span class="px-sr-only" aria-live="polite">{{ safeValue }} 分</span>
  </fieldset>
</template>
