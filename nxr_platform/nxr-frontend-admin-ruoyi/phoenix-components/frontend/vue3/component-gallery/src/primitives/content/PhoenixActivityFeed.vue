<script setup lang="ts">
import { computed } from 'vue'
import { normalizeAppearance, safeImageUrl } from './safety'
import type { PhoenixContentAppearance } from './safety'

export type PhoenixActivityTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'

export interface PhoenixActivityItem {
  id: string | number
  actor: string
  action: string
  target?: string
  description?: string
  timestamp?: string
  avatar?: string
  tone?: PhoenixActivityTone
  actionLabel?: string
}

const props = withDefaults(defineProps<{
  items: PhoenixActivityItem[]
  appearance?: PhoenixContentAppearance
  title?: string
  loading?: boolean
  disabled?: boolean
  hasMore?: boolean
  emptyText?: string
}>(), {
  appearance: 'modern',
  title: '最新动态',
  loading: false,
  disabled: false,
  hasMore: false,
  emptyText: '暂无动态',
})

const emit = defineEmits<{
  select: [item: PhoenixActivityItem, index: number]
  action: [item: PhoenixActivityItem]
  'load-more': []
}>()

const appearanceValue = computed(() => normalizeAppearance(props.appearance))
</script>

<template>
  <section class="px-activity-feed px-content-card" :data-appearance="appearanceValue" :aria-busy="loading">
    <header class="px-content-header"><div><h3>{{ title }}</h3><span>{{ items.length }} 条记录</span></div></header>
    <p v-if="!items.length && !loading" class="px-content-empty" role="status">{{ emptyText }}</p>
    <ol v-else class="px-activity-feed__list" aria-label="动态列表">
      <li v-for="(item, index) in items" :key="item.id" :class="`is-${item.tone || 'neutral'}`">
        <span class="px-content-avatar"><img v-if="safeImageUrl(item.avatar)" :src="safeImageUrl(item.avatar)" :alt="item.actor" /><strong v-else aria-hidden="true">{{ item.actor.slice(0, 1) }}</strong></span>
        <article tabindex="0" role="button" :aria-label="`${item.actor}${item.action}${item.target || ''}`" @click="!disabled && emit('select', item, index)" @keydown.enter.prevent="!disabled && emit('select', item, index)" @keydown.space.prevent="!disabled && emit('select', item, index)">
          <p><strong>{{ item.actor }}</strong> {{ item.action }} <b v-if="item.target">{{ item.target }}</b></p>
          <small v-if="item.description">{{ item.description }}</small>
          <time v-if="item.timestamp">{{ item.timestamp }}</time>
        </article>
        <button v-if="item.actionLabel" type="button" :disabled="disabled" @click="emit('action', item)">{{ item.actionLabel }}</button>
      </li>
    </ol>
    <footer v-if="loading || hasMore" class="px-content-footer">
      <span v-if="loading" role="status">动态加载中</span>
      <button v-else type="button" :disabled="disabled" @click="emit('load-more')">加载更多</button>
    </footer>
  </section>
</template>
