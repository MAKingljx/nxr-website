<script setup lang="ts">
import { computed } from 'vue'

export interface PhoenixSession {
  id: string | number
  device: string
  browser?: string
  location?: string
  ip?: string
  lastActive: string
  createdAt?: string
  expiresAt?: string
  current?: boolean
  revoking?: boolean
}
const props = withDefaults(defineProps<{
  sessions?: PhoenixSession[]
  currentSessionId?: string | number | null
  loading?: boolean
  disabled?: boolean
  title?: string
}>(), { sessions: () => [], currentSessionId: null, loading: false, disabled: false, title: '登录设备' })
const emit = defineEmits<{
  revoke: [session: PhoenixSession]
  revokeOthers: [sessionIds: Array<string | number>]
  refresh: []
}>()
const uniqueSessions = computed(() => props.sessions.filter((session, index, list) => list.findIndex((item) => item.id === session.id) === index))
function current(session: PhoenixSession) { return session.current || session.id === props.currentSessionId }
const otherIds = computed(() => uniqueSessions.value.filter((session) => !current(session)).map((session) => session.id))
</script>

<template>
  <section class="px-session-manager" :aria-label="title" :aria-busy="loading">
    <header><div><h3>{{ title }}</h3><p>共 {{ uniqueSessions.length }} 个会话</p></div><button type="button" class="is-quiet" :disabled="disabled || loading" @click="emit('refresh')">刷新</button></header>
    <ul v-if="uniqueSessions.length">
      <li v-for="session in uniqueSessions" :key="session.id" :class="{ 'is-current': current(session) }">
        <span class="px-session-manager__device" aria-hidden="true">{{ current(session) ? '●' : '○' }}</span>
        <div>
          <strong>{{ session.device }}</strong>
          <small v-if="session.browser || session.location"><template v-if="session.browser">{{ session.browser }}</template><template v-if="session.browser && session.location"> · </template><template v-if="session.location">{{ session.location }}</template></small>
          <small><template v-if="session.ip">{{ session.ip }} · </template>最近活动：{{ session.lastActive }}</small>
          <small v-if="session.expiresAt">有效期至：{{ session.expiresAt }}</small>
        </div>
        <span v-if="current(session)" class="px-session-manager__current">当前设备</span>
        <button v-else type="button" class="is-danger" :disabled="disabled || loading || session.revoking" :aria-label="`退出${session.device}`" @click="emit('revoke', session)">{{ session.revoking ? '退出中' : '退出登录' }}</button>
      </li>
    </ul>
    <p v-else-if="!loading" class="px-admin-state" role="status">暂无登录会话</p>
    <footer v-if="otherIds.length"><button type="button" class="is-danger" :disabled="disabled || loading" @click="emit('revokeOthers', otherIds)">退出其他全部设备</button></footer>
  </section>
</template>
