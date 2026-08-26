<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    value?: string | number
    max?: number
    dot?: boolean
    hidden?: boolean
    showZero?: boolean
    variant?: 'primary' | 'success' | 'warning' | 'danger' | 'neutral'
    label?: string
  }>(),
  {
    value: 0,
    max: 99,
    dot: false,
    hidden: false,
    showZero: false,
    variant: 'danger',
    label: '未读消息',
  },
)

const content = computed(() => {
  if (props.dot) return ''
  if (typeof props.value === 'number' && props.value > props.max) return `${props.max}+`
  return String(props.value)
})
const visible = computed(() => !props.hidden && (props.dot || props.showZero || props.value !== 0))
const ariaLabel = computed(() => props.dot ? props.label : `${props.label}：${content.value}`)
</script>

<template>
  <span class="px-badge">
    <slot></slot>
    <span
      v-if="visible"
      class="px-badge__content"
      :class="[`px-badge__content--${variant}`, { 'is-dot': dot, 'is-standalone': !$slots.default }]"
      role="status"
      :aria-label="ariaLabel"
    ><span aria-hidden="true">{{ content }}</span></span>
  </span>
</template>
