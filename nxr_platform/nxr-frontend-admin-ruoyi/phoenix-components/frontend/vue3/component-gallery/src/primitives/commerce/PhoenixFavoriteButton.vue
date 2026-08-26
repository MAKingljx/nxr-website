<script setup lang="ts">
import { computed } from 'vue'
import { clampInteger } from './utils'

const props = withDefaults(defineProps<{
  modelValue?: boolean
  count?: number
  label?: string
  disabled?: boolean
  loading?: boolean
}>(), { modelValue: false, count: undefined, label: '收藏', disabled: false, loading: false })
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; toggle: [value: boolean] }>()
const safeCount = computed(() => props.count == null ? null : clampInteger(props.count, 0, 999999999))
function toggle() {
  if (props.disabled || props.loading) return
  emit('update:modelValue', !props.modelValue); emit('toggle', !props.modelValue)
}
</script>

<template>
  <button class="px-favorite-button" :class="{ 'is-active': modelValue }" type="button" :aria-pressed="modelValue" :aria-label="modelValue ? `取消${label}` : label" :disabled="disabled || loading" @click="toggle"><span aria-hidden="true">{{ modelValue ? '♥' : '♡' }}</span><span>{{ loading ? '处理中' : modelValue ? `已${label}` : label }}</span><small v-if="safeCount != null">{{ safeCount }}</small></button>
</template>
