<script setup lang="ts">
withDefaults(
  defineProps<{
    variant?: 'primary' | 'success' | 'warning' | 'danger' | 'neutral'
    size?: 'small' | 'medium'
    closable?: boolean
    rounded?: boolean
    closeLabel?: string
  }>(),
  {
    variant: 'neutral',
    size: 'medium',
    closable: false,
    rounded: false,
    closeLabel: '关闭标签',
  },
)

defineEmits<{
  close: [event: MouseEvent]
}>()
</script>

<template>
  <span class="px-tag" :class="[`px-tag--${variant}`, `px-tag--${size}`, { 'is-rounded': rounded }]">
    <slot>标签</slot>
    <button v-if="closable" type="button" :aria-label="closeLabel" @click="$emit('close', $event)">×</button>
  </span>
</template>
