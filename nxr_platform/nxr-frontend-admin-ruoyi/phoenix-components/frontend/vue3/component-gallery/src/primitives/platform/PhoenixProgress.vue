<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  percentage?: number
  status?: 'default' | 'success' | 'warning' | 'danger'
  size?: 'small' | 'medium' | 'large'
  label?: string
  showText?: boolean
}>(), {
  percentage: 0,
  status: 'default',
  size: 'medium',
  label: '完成进度',
  showText: true,
})

const value = computed(() => Math.min(100, Math.max(0, Number.isFinite(props.percentage) ? props.percentage : 0)))
</script>

<template>
  <div class="px-progress" :class="[`px-progress--${size}`, `px-progress--${status}`]">
    <div class="px-progress__track" role="progressbar" :aria-label="label" aria-valuemin="0" aria-valuemax="100" :aria-valuenow="value"><span :style="{ width: `${value}%` }"></span></div>
    <span v-if="showText" class="px-progress__text"><slot name="text" :value="value">{{ Math.round(value) }}%</slot></span>
  </div>
</template>
