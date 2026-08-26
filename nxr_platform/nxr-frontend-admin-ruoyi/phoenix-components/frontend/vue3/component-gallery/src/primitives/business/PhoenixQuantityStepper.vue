<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  modelValue?: number
  min?: number
  max?: number
  step?: number
  disabled?: boolean
  readonly?: boolean
  label?: string
}>(), {
  modelValue: 1, min: 1, max: 99, step: 1, disabled: false, readonly: false, label: '数量',
})

const emit = defineEmits<{
  'update:modelValue': [value: number]
  change: [value: number]
}>()

const lower = computed(() => Number.isFinite(props.min) ? Math.trunc(props.min) : 1)
const upper = computed(() => Number.isFinite(props.max) ? Math.max(lower.value, Math.trunc(props.max)) : 99)
const increment = computed(() => Number.isFinite(props.step) && props.step > 0 ? Math.max(1, Math.trunc(props.step)) : 1)
const current = computed(() => clamp(props.modelValue))

function clamp(value: number) {
  const safe = Number.isFinite(value) ? Math.trunc(value) : lower.value
  return Math.min(upper.value, Math.max(lower.value, safe))
}

function update(value: number) {
  if (props.disabled || props.readonly) return
  const next = clamp(value)
  emit('update:modelValue', next)
  emit('change', next)
}

function onInput(event: Event) {
  update(Number((event.target as HTMLInputElement).value))
}
</script>

<template>
  <div class="px-quantity-stepper" :aria-label="label">
    <button type="button" aria-label="减少数量" :disabled="disabled || readonly || current <= lower" @click="update(current - increment)">−</button>
    <input
      type="number" inputmode="numeric" :value="current" :min="lower" :max="upper" :step="increment"
      :aria-label="label" :disabled="disabled" :readonly="readonly" @change="onInput"
    >
    <button type="button" aria-label="增加数量" :disabled="disabled || readonly || current >= upper" @click="update(current + increment)">+</button>
  </div>
</template>
