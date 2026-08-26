<script setup lang="ts">
import { computed } from 'vue'

type StreamState = 'offline' | 'live' | 'paused' | 'ended' | 'scheduled'

const props = withDefaults(defineProps<{
  status?: StreamState
  title?: string
  viewers?: number
  scheduledAt?: string
  startedAt?: string
  actionLabel?: string
  actionDisabled?: boolean
}>(), {
  status: 'offline', title: '直播间', viewers: 0, scheduledAt: '', startedAt: '', actionLabel: '', actionDisabled: false,
})

const emit = defineEmits<{ action: [status: StreamState] }>()
const labels: Record<StreamState, string> = { offline: '未开播', live: '直播中', paused: '已暂停', ended: '已结束', scheduled: '待开播' }
const label = computed(() => labels[props.status] || labels.offline)
const safeViewers = computed(() => Number.isFinite(props.viewers) ? Math.max(0, Math.trunc(props.viewers)) : 0)
</script>

<template>
  <section class="px-stream-status" :class="`is-${status}`" :aria-label="`直播状态：${label}`" role="status">
    <div class="px-stream-status__signal" aria-hidden="true"><i></i><i></i><i></i></div>
    <div><span>{{ label }}</span><h3>{{ title }}</h3><p v-if="status === 'live'">{{ safeViewers }} 人观看<span v-if="startedAt"> · {{ startedAt }} 开始</span></p><p v-else-if="status === 'scheduled' && scheduledAt">{{ scheduledAt }}</p></div>
    <button v-if="actionLabel" type="button" :disabled="actionDisabled" @click="emit('action', status)">{{ actionLabel }}</button>
  </section>
</template>
