<script setup lang="ts">
import { computed } from 'vue'
import { normalizeAppearance } from './safety'
import type { PhoenixContentAppearance } from './safety'

export type PhoenixNotificationTone = 'info' | 'success' | 'warning' | 'danger'
export type PhoenixNotificationFilter = 'all' | 'unread'

export interface PhoenixNotificationItem {
  id: string | number
  title: string
  description?: string
  timestamp?: string
  read?: boolean
  tone?: PhoenixNotificationTone
  actionLabel?: string
  dismissible?: boolean
}

const props = withDefaults(defineProps<{
  items: PhoenixNotificationItem[]
  filter?: PhoenixNotificationFilter
  appearance?: PhoenixContentAppearance
  title?: string
  loading?: boolean
  disabled?: boolean
  emptyText?: string
}>(), {
  filter: 'all',
  appearance: 'modern',
  title: '通知中心',
  loading: false,
  disabled: false,
  emptyText: '暂无通知',
})

const emit = defineEmits<{
  'update:filter': [filter: PhoenixNotificationFilter]
  select: [item: PhoenixNotificationItem]
  'mark-read': [item: PhoenixNotificationItem]
  'mark-all-read': []
  dismiss: [item: PhoenixNotificationItem]
  action: [item: PhoenixNotificationItem]
}>()

const appearanceValue = computed(() => normalizeAppearance(props.appearance))
const unreadCount = computed(() => props.items.filter((item) => !item.read).length)
const visibleItems = computed(() => props.filter === 'unread' ? props.items.filter((item) => !item.read) : props.items)
</script>

<template>
  <section class="px-notification-center px-content-card" :data-appearance="appearanceValue" :aria-busy="loading">
    <header class="px-content-header">
      <div><h3>{{ title }}</h3><span>{{ unreadCount }} 条未读</span></div>
      <button type="button" :disabled="disabled || loading || unreadCount === 0" @click="emit('mark-all-read')">全部已读</button>
    </header>
    <div class="px-content-segmented" aria-label="通知筛选">
      <button type="button" :aria-pressed="filter === 'all'" :disabled="disabled" @click="emit('update:filter', 'all')">全部</button>
      <button type="button" :aria-pressed="filter === 'unread'" :disabled="disabled" @click="emit('update:filter', 'unread')">未读 {{ unreadCount }}</button>
    </div>
    <p v-if="loading" class="px-content-empty" role="status">通知加载中</p>
    <p v-else-if="visibleItems.length === 0" class="px-content-empty" role="status">{{ emptyText }}</p>
    <ol v-else class="px-content-list" aria-label="通知列表">
      <li v-for="item in visibleItems" :key="item.id" class="px-notification-center__item" :class="[`is-${item.tone || 'info'}`, { 'is-unread': !item.read }]">
        <button class="px-notification-center__main" type="button" :disabled="disabled" @click="emit('select', item)">
          <span class="px-content-dot" aria-hidden="true"></span>
          <span><strong>{{ item.title }}</strong><small v-if="item.description">{{ item.description }}</small><time v-if="item.timestamp">{{ item.timestamp }}</time></span>
        </button>
        <div class="px-content-actions">
          <button v-if="!item.read" type="button" :disabled="disabled" :aria-label="`标记已读：${item.title}`" @click="emit('mark-read', item)">已读</button>
          <button v-if="item.actionLabel" type="button" :disabled="disabled" @click="emit('action', item)">{{ item.actionLabel }}</button>
          <button v-if="item.dismissible" type="button" :disabled="disabled" :aria-label="`移除通知：${item.title}`" @click="emit('dismiss', item)">移除</button>
        </div>
      </li>
    </ol>
  </section>
</template>
