<script setup lang="ts">
import { computed } from 'vue'
import { clampInteger, clampNumber } from './utils'

const props = withDefaults(defineProps<{
  modelValue?: number
  max?: number
  count?: number
  label?: string
  readonly?: boolean
  disabled?: boolean
}>(), { modelValue: 0, max: 5, count: undefined, label: '评分', readonly: false, disabled: false })
const emit = defineEmits<{ 'update:modelValue': [value: number]; change: [value: number] }>()
const safeMax = computed(() => clampInteger(props.max, 1, 10))
const value = computed(() => clampNumber(props.modelValue, 0, safeMax.value))
const safeCount = computed(() => props.count == null ? null : clampInteger(props.count, 0, 999999999))
function choose(next: number) {
  if (props.disabled || props.readonly) return
  const normalized = clampInteger(next, 0, safeMax.value)
  emit('update:modelValue', normalized); emit('change', normalized)
}
function onKeydown(event: KeyboardEvent) {
  if (event.key === 'ArrowRight' || event.key === 'ArrowUp') choose(Math.floor(value.value) + 1)
  else if (event.key === 'ArrowLeft' || event.key === 'ArrowDown') choose(Math.ceil(value.value) - 1)
  else return
  event.preventDefault()
}
</script>

<template>
  <div class="px-commerce-rating" role="radiogroup" :aria-label="label" :aria-disabled="disabled || readonly" @keydown="onKeydown">
    <button v-for="star in safeMax" :key="star" type="button" role="radio" :aria-checked="star === Math.round(value)" :aria-label="`${star} 分`" :disabled="disabled || readonly" :class="{ 'is-active': star <= value }" @click="choose(star)">★</button>
    <strong aria-live="polite">{{ value.toFixed(1) }}</strong><span v-if="safeCount != null">{{ safeCount }} 条评价</span>
  </div>
</template>
