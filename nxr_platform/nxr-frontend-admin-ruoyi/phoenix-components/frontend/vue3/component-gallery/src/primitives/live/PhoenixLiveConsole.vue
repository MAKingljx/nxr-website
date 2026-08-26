<script setup lang="ts">
import { computed } from 'vue'
import { safeCount } from './safety'

export type PhoenixLiveStatus = 'offline' | 'scheduled' | 'live' | 'paused' | 'ended'
export type PhoenixLiveAction = 'start' | 'pause' | 'resume' | 'end'

const props = withDefaults(defineProps<{
  title?: string
  status?: PhoenixLiveStatus
  viewers?: number
  likes?: number
  durationLabel?: string
  actions?: PhoenixLiveAction[]
  disabled?: boolean
  busy?: boolean
}>(), {
  title: '直播控制台',
  status: 'offline',
  viewers: 0,
  likes: 0,
  durationLabel: '',
  actions: () => [],
  disabled: false,
  busy: false,
})

const emit = defineEmits<{
  'request-action': [action: PhoenixLiveAction]
  refresh: []
}>()

const statusLabels: Record<PhoenixLiveStatus, string> = {
  offline: '未开播',
  scheduled: '待开播',
  live: '直播中',
  paused: '已暂停',
  ended: '已结束',
}

const actionLabels: Record<PhoenixLiveAction, string> = {
  start: '开始直播',
  pause: '暂停直播',
  resume: '继续直播',
  end: '结束直播',
}

const statusLabel = computed(() => statusLabels[props.status] ?? statusLabels.offline)
const visibleActions = computed(() => [...new Set(props.actions)].filter((action) => action in actionLabels))
const safeViewers = computed(() => safeCount(props.viewers))
const safeLikes = computed(() => safeCount(props.likes))

function requestAction(action: PhoenixLiveAction) {
  if (!props.disabled && !props.busy) emit('request-action', action)
}
</script>

<template>
  <section class="px-live-console" :aria-label="title" :aria-busy="busy">
    <header>
      <div>
        <span class="px-live-console__status" :class="`is-${status}`" role="status">{{ statusLabel }}</span>
        <h3>{{ title }}</h3>
      </div>
      <button type="button" :disabled="disabled || busy" aria-label="刷新直播数据" @click="emit('refresh')">刷新</button>
    </header>

    <dl class="px-live-console__metrics">
      <div><dt>当前在线</dt><dd>{{ safeViewers }} 人</dd></div>
      <div><dt>点赞数量</dt><dd>{{ safeLikes }}</dd></div>
      <div><dt>直播时长</dt><dd>{{ durationLabel || '—' }}</dd></div>
    </dl>

    <div v-if="visibleActions.length" class="px-live-console__actions" aria-label="直播操作">
      <button
        v-for="action in visibleActions"
        :key="action"
        type="button"
        :class="{ 'is-danger': action === 'end' }"
        :disabled="disabled || busy"
        @click="requestAction(action)"
      >
        {{ actionLabels[action] }}
      </button>
    </div>
  </section>
</template>
