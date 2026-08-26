<script setup lang="ts">
import { computed } from 'vue'
import { safeCount, safeImageUrl, safeReplayUrl } from './safety'

export interface PhoenixLiveReplay {
  id: string | number
  title: string
  url: string
  thumbnail?: string
  durationLabel?: string
  createdAt?: string
  views?: number
  status?: 'available' | 'processing' | 'failed'
  disabled?: boolean
}

export interface PhoenixReplayRequest {
  replay: PhoenixLiveReplay
  url: string
}

const props = withDefaults(defineProps<{
  items: PhoenixLiveReplay[]
  title?: string
  selectedId?: string | number | null
  disabled?: boolean
  emptyText?: string
}>(), {
  title: '直播回放',
  selectedId: null,
  disabled: false,
  emptyText: '暂无直播回放',
})

const emit = defineEmits<{
  'update:selectedId': [id: string | number]
  select: [replay: PhoenixLiveReplay]
  'request-play': [request: PhoenixReplayRequest]
  'request-remove': [replay: PhoenixLiveReplay]
}>()

const count = computed(() => safeCount(props.items.length, 10_000))
const statusLabels = { available: '可播放', processing: '处理中', failed: '处理失败' } as const

function unavailable(item: PhoenixLiveReplay) {
  return props.disabled || item.disabled
}

function select(item: PhoenixLiveReplay) {
  if (unavailable(item)) return
  emit('update:selectedId', item.id)
  emit('select', item)
}

function requestPlay(item: PhoenixLiveReplay) {
  const url = safeReplayUrl(item.url)
  if (!unavailable(item) && item.status !== 'processing' && item.status !== 'failed' && url) emit('request-play', { replay: item, url })
}
</script>

<template>
  <section class="px-replay-list" :aria-label="title">
    <header><h3>{{ title }}</h3><strong>{{ count }} 条</strong></header>
    <p v-if="!items.length" class="px-live-state" role="status">{{ emptyText }}</p>
    <ul v-else>
      <li v-for="item in items" :key="item.id" :class="{ 'is-selected': selectedId === item.id }">
        <button class="px-replay-list__select" type="button" :disabled="unavailable(item)" :aria-pressed="selectedId === item.id" @click="select(item)">
          <span class="px-replay-list__image">
            <img v-if="safeImageUrl(item.thumbnail)" :src="safeImageUrl(item.thumbnail)" :alt="item.title" loading="lazy">
            <span v-else aria-hidden="true">回放</span>
          </span>
          <span class="px-replay-list__content">
            <strong>{{ item.title }}</strong>
            <small>{{ statusLabels[item.status || 'available'] }}<template v-if="item.durationLabel"> · {{ item.durationLabel }}</template></small>
            <small><template v-if="item.createdAt">{{ item.createdAt }} · </template>{{ safeCount(item.views ?? 0) }} 次观看</small>
          </span>
        </button>
        <div class="px-replay-list__actions">
          <button type="button" :disabled="unavailable(item) || item.status === 'processing' || item.status === 'failed' || !safeReplayUrl(item.url)" @click="requestPlay(item)">播放回放</button>
          <button type="button" :disabled="unavailable(item)" @click="emit('request-remove', item)">请求删除</button>
        </div>
      </li>
    </ul>
  </section>
</template>
