<script setup lang="ts">
export interface PhoenixRealtimeFeedItem {
  id: string | number
  title?: string
  message: string
  actor?: string
  timestamp?: string
  status?: 'default' | 'success' | 'warning' | 'danger'
}

withDefaults(
  defineProps<{
    items: PhoenixRealtimeFeedItem[]
    title?: string
    ariaLabel?: string
    loading?: boolean
    loadingText?: string
    emptyText?: string
    showRefresh?: boolean
  }>(),
  {
    title: '实时动态',
    ariaLabel: '实时动态列表',
    loading: false,
    loadingText: '动态加载中',
    emptyText: '暂无动态',
    showRefresh: false,
  },
)

const emit = defineEmits<{
  select: [item: PhoenixRealtimeFeedItem, index: number]
  refresh: []
}>()

function select(item: PhoenixRealtimeFeedItem, index: number) {
  emit('select', item, index)
}

function onKeydown(event: KeyboardEvent, item: PhoenixRealtimeFeedItem, index: number) {
  if (!['Enter', ' '].includes(event.key)) return
  event.preventDefault()
  select(item, index)
}
</script>

<template>
  <section class="px-realtime-feed" :aria-label="ariaLabel" :aria-busy="loading">
    <header class="px-realtime-feed__header">
      <slot name="title"><h3>{{ title }}</h3></slot>
      <button v-if="showRefresh" type="button" aria-label="刷新动态" :disabled="loading" @click="emit('refresh')">刷新</button>
    </header>
    <div v-if="loading" class="px-advanced-state" role="status">{{ loadingText }}</div>
    <div v-else-if="items.length === 0" class="px-advanced-state" role="status">{{ emptyText }}</div>
    <div v-else class="px-realtime-feed__list" role="feed" aria-live="polite">
      <article
        v-for="(item, index) in items"
        :key="item.id"
        class="px-realtime-feed__item"
        :class="`is-${item.status || 'default'}`"
        tabindex="0"
        :aria-posinset="index + 1"
        :aria-setsize="items.length"
        @click="select(item, index)"
        @keydown="onKeydown($event, item, index)"
      >
        <span class="px-realtime-feed__marker" aria-hidden="true"></span>
        <div>
          <strong v-if="item.title">{{ item.title }}</strong>
          <p>{{ item.message }}</p>
          <div v-if="item.actor || item.timestamp" class="px-realtime-feed__meta">
            <span v-if="item.actor">{{ item.actor }}</span>
            <time v-if="item.timestamp">{{ item.timestamp }}</time>
          </div>
        </div>
      </article>
    </div>
  </section>
</template>
