<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    src?: string
    alt?: string
    name?: string
    size?: 'small' | 'medium' | 'large' | number
    shape?: 'circle' | 'square'
    status?: 'online' | 'offline' | 'busy' | 'away'
  }>(),
  {
    src: '',
    alt: '用户头像',
    name: '用户',
    size: 'medium',
    shape: 'circle',
    status: undefined,
  },
)

const emit = defineEmits<{
  error: [event: Event]
}>()

const failed = ref(false)
const initials = computed(() => Array.from(props.name.trim() || '用户').slice(0, 2).join('').toUpperCase())
const numericSize = computed(() => typeof props.size === 'number' ? `${Math.max(24, props.size)}px` : undefined)
const statusLabels = { online: '在线', offline: '离线', busy: '忙碌', away: '离开' }

watch(() => props.src, () => { failed.value = false })

function onError(event: Event) {
  failed.value = true
  emit('error', event)
}
</script>

<template>
  <span
    class="px-avatar"
    :class="[`px-avatar--${typeof size === 'number' ? 'custom' : size}`, `px-avatar--${shape}`]"
    :style="numericSize ? { width: numericSize, height: numericSize } : undefined"
  >
    <img v-if="src && !failed" :src="src" :alt="alt" @error="onError">
    <span v-else class="px-avatar__fallback" role="img" :aria-label="alt">{{ initials }}</span>
    <span v-if="status" class="px-avatar__status" :class="`px-avatar__status--${status}`" :aria-label="statusLabels[status]" role="status"></span>
  </span>
</template>
