<script setup lang="ts">
import { computed } from 'vue'
import { safeCount } from './safety'

export type PhoenixModerationDecision = 'approve' | 'reject'

export interface PhoenixModerationItem {
  id: string | number
  sender: string
  content: string
  reason?: string
  submittedAt?: string
  risk?: 'low' | 'medium' | 'high'
  disabled?: boolean
}

const props = withDefaults(defineProps<{
  items: PhoenixModerationItem[]
  selectedId?: string | number | null
  processingId?: string | number | null
  title?: string
  disabled?: boolean
  emptyText?: string
}>(), {
  selectedId: null,
  processingId: null,
  title: '消息审核',
  disabled: false,
  emptyText: '暂无待审核消息',
})

const emit = defineEmits<{
  'update:selectedId': [id: string | number]
  select: [item: PhoenixModerationItem]
  'request-decision': [item: PhoenixModerationItem, decision: PhoenixModerationDecision]
  'request-mute': [item: PhoenixModerationItem]
}>()

const count = computed(() => safeCount(props.items.length, 10_000))
const riskLabels = { low: '低风险', medium: '中风险', high: '高风险' } as const

function isUnavailable(item: PhoenixModerationItem) {
  return props.disabled || item.disabled || props.processingId === item.id
}

function select(item: PhoenixModerationItem) {
  if (isUnavailable(item)) return
  emit('update:selectedId', item.id)
  emit('select', item)
}

function decide(item: PhoenixModerationItem, decision: PhoenixModerationDecision) {
  if (!isUnavailable(item)) emit('request-decision', item, decision)
}

function mute(item: PhoenixModerationItem) {
  if (!isUnavailable(item)) emit('request-mute', item)
}
</script>

<template>
  <section class="px-moderation-queue" :aria-label="title">
    <header><h3>{{ title }}</h3><strong>{{ count }} 条</strong></header>
    <p v-if="!items.length" class="px-live-state" role="status">{{ emptyText }}</p>
    <ol v-else>
      <li v-for="item in items" :key="item.id" :class="[{ 'is-selected': selectedId === item.id }, `is-${item.risk || 'low'}`]" :aria-busy="processingId === item.id">
        <button class="px-moderation-queue__message" type="button" :disabled="isUnavailable(item)" :aria-pressed="selectedId === item.id" @click="select(item)">
          <span><strong>{{ item.sender }}</strong><em>{{ riskLabels[item.risk || 'low'] }}</em></span>
          <span>{{ item.content }}</span>
          <small v-if="item.reason || item.submittedAt">{{ item.reason }}<template v-if="item.reason && item.submittedAt"> · </template>{{ item.submittedAt }}</small>
        </button>
        <div class="px-moderation-queue__actions">
          <button type="button" :disabled="isUnavailable(item)" @click="decide(item, 'approve')">通过</button>
          <button type="button" :disabled="isUnavailable(item)" @click="decide(item, 'reject')">拦截</button>
          <button type="button" :disabled="isUnavailable(item)" @click="mute(item)">请求禁言</button>
        </div>
      </li>
    </ol>
  </section>
</template>
