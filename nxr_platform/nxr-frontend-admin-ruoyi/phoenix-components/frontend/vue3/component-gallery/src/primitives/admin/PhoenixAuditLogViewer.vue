<script setup lang="ts">
import { computed } from 'vue'

export type PhoenixAuditSeverity = 'info' | 'success' | 'warning' | 'danger'
export interface PhoenixAuditLog {
  id: string | number
  action: string
  operator: string
  time: string
  target?: string
  ip?: string
  detail?: string
  severity?: PhoenixAuditSeverity
}
const props = withDefaults(defineProps<{
  logs?: PhoenixAuditLog[]
  selectedId?: string | number | null
  loading?: boolean
  hasMore?: boolean
  title?: string
  emptyText?: string
}>(), { logs: () => [], selectedId: null, loading: false, hasMore: false, title: '审计日志', emptyText: '暂无审计记录' })
const emit = defineEmits<{
  'update:selectedId': [id: string | number]
  select: [log: PhoenixAuditLog]
  loadMore: []
}>()
const uniqueLogs = computed(() => props.logs.filter((log, index, list) => list.findIndex((item) => item.id === log.id) === index))
function select(log: PhoenixAuditLog) {
  emit('update:selectedId', log.id)
  emit('select', log)
}
</script>

<template>
  <section class="px-audit-log" :aria-label="title" :aria-busy="loading">
    <header><h3>{{ title }}</h3><span>{{ uniqueLogs.length }} 条</span></header>
    <ol v-if="uniqueLogs.length">
      <li v-for="log in uniqueLogs" :key="log.id">
        <button type="button" :class="[`is-${log.severity || 'info'}`, { 'is-selected': selectedId === log.id }]" :aria-current="selectedId === log.id ? 'true' : undefined" @click="select(log)">
          <span class="px-audit-log__dot" aria-hidden="true"></span>
          <span><strong>{{ log.action }}</strong><small>{{ log.operator }}<template v-if="log.target"> · {{ log.target }}</template></small></span>
          <time>{{ log.time }}</time>
          <span v-if="log.detail" class="px-audit-log__detail">{{ log.detail }}</span>
          <code v-if="log.ip">{{ log.ip }}</code>
        </button>
      </li>
    </ol>
    <p v-else-if="!loading" class="px-admin-state" role="status">{{ emptyText }}</p>
    <footer v-if="loading || hasMore"><button type="button" :disabled="loading" @click="emit('loadMore')">{{ loading ? '加载中' : '加载更多' }}</button></footer>
  </section>
</template>
