<script setup lang="ts">
import { computed, useId } from 'vue'

const props = withDefaults(defineProps<{
  modelValue?: number
  id?: string
  name?: string
  label?: string
  min?: number
  max?: number
  step?: number
  disabled?: boolean
  showValue?: boolean
}>(), {
  modelValue: 0, id: undefined, name: undefined, label: '滑块', min: 0, max: 100, step: 1,
  disabled: false, showValue: true,
})
const emit = defineEmits<{ 'update:modelValue': [value: number]; change: [value: number] }>()
const uid = useId()
const fieldName = computed(() => props.name || `phoenix-slider-${uid}`)
const bounds = computed(() => ({ min: Math.min(props.min, props.max), max: Math.max(props.min, props.max) }))
const safeStep = computed(() => Number.isFinite(props.step) && props.step > 0 ? props.step : 1)
const safeValue = computed(() => Math.min(Math.max(Number.isFinite(props.modelValue) ? props.modelValue : bounds.value.min, bounds.value.min), bounds.value.max))
function read(event: Event, commit = false) {
  const value = Math.min(Math.max(Number((event.target as HTMLInputElement).value), bounds.value.min), bounds.value.max)
  emit('update:modelValue', value)
  if (commit) emit('change', value)
}
</script>

<template>
  <div class="px-slider" :class="{ 'is-disabled': disabled }">
    <input
      :id="id" type="range" :name="fieldName" :value="safeValue" :aria-label="label"
      :min="bounds.min" :max="bounds.max" :step="safeStep" :disabled="disabled"
      @input="read($event)" @change="read($event, true)"
    >
    <output v-if="showValue" :for="id">{{ safeValue }}</output>
  </div>
</template>
