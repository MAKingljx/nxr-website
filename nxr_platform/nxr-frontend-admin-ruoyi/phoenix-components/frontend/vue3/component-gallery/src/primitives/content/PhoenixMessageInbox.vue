<script setup lang="ts">
import { computed } from 'vue'
import { normalizeAppearance, safeImageUrl } from './safety'
import type { PhoenixContentAppearance } from './safety'

export type PhoenixInboxFolder = 'inbox' | 'starred' | 'archived'

export interface PhoenixMessageThread {
  id: string | number
  sender: string
  subject: string
  preview?: string
  timestamp?: string
  avatar?: string
  unread?: boolean
  starred?: boolean
  archived?: boolean
  messageCount?: number
}

const props = withDefaults(defineProps<{
  threads: PhoenixMessageThread[]
  selectedId?: string | number
  query?: string
  folder?: PhoenixInboxFolder
  appearance?: PhoenixContentAppearance
  title?: string
  loading?: boolean
  disabled?: boolean
  emptyText?: string
}>(), {
  selectedId: undefined,
  query: '',
  folder: 'inbox',
  appearance: 'modern',
  title: '消息收件箱',
  loading: false,
  disabled: false,
  emptyText: '暂无消息',
})

const emit = defineEmits<{
  'update:selectedId': [id: string | number]
  'update:query': [query: string]
  'update:folder': [folder: PhoenixInboxFolder]
  select: [thread: PhoenixMessageThread]
  compose: []
  archive: [thread: PhoenixMessageThread]
  star: [thread: PhoenixMessageThread, starred: boolean]
}>()

const appearanceValue = computed(() => normalizeAppearance(props.appearance))
const visibleThreads = computed(() => {
  const query = props.query.trim().toLocaleLowerCase('zh-CN')
  return props.threads.filter((thread) => {
    const folderMatch = props.folder === 'starred' ? thread.starred : props.folder === 'archived' ? thread.archived : !thread.archived
    const queryMatch = !query || `${thread.sender} ${thread.subject} ${thread.preview ?? ''}`.toLocaleLowerCase('zh-CN').includes(query)
    return folderMatch && queryMatch
  })
})

function select(thread: PhoenixMessageThread) {
  if (props.disabled) return
  emit('update:selectedId', thread.id)
  emit('select', thread)
}
</script>

<template>
  <section class="px-message-inbox px-content-card" :data-appearance="appearanceValue" :aria-busy="loading">
    <header class="px-content-header">
      <div><h3>{{ title }}</h3><span>{{ threads.length }} 个会话</span></div>
      <button type="button" :disabled="disabled" @click="emit('compose')">写消息</button>
    </header>
    <label class="px-message-inbox__search"><span class="px-sr-only">搜索消息</span><input type="search" :value="query" placeholder="搜索发件人或主题" :disabled="disabled" @input="emit('update:query', ($event.target as HTMLInputElement).value)" /></label>
    <nav class="px-content-segmented" aria-label="消息文件夹">
      <button v-for="item in ([['inbox', '收件箱'], ['starred', '已标星'], ['archived', '已归档']] as const)" :key="item[0]" type="button" :aria-pressed="folder === item[0]" :disabled="disabled" @click="emit('update:folder', item[0])">{{ item[1] }}</button>
    </nav>
    <p v-if="loading" class="px-content-empty" role="status">消息加载中</p>
    <p v-else-if="visibleThreads.length === 0" class="px-content-empty" role="status">{{ emptyText }}</p>
    <ul v-else class="px-content-list" aria-label="消息列表">
      <li v-for="thread in visibleThreads" :key="thread.id" class="px-message-inbox__item" :class="{ 'is-selected': selectedId === thread.id, 'is-unread': thread.unread }">
        <button class="px-message-inbox__main" type="button" :aria-current="selectedId === thread.id ? 'true' : undefined" :disabled="disabled" @click="select(thread)">
          <span class="px-content-avatar"><img v-if="safeImageUrl(thread.avatar)" :src="safeImageUrl(thread.avatar)" :alt="thread.sender" /><strong v-else aria-hidden="true">{{ thread.sender.slice(0, 1) }}</strong></span>
          <span class="px-message-inbox__copy"><span><strong>{{ thread.sender }}</strong><time v-if="thread.timestamp">{{ thread.timestamp }}</time></span><b>{{ thread.subject }}</b><small v-if="thread.preview">{{ thread.preview }}</small></span>
          <span v-if="thread.messageCount && thread.messageCount > 1" class="px-content-count">{{ thread.messageCount }}</span>
        </button>
        <div class="px-content-actions">
          <button type="button" :disabled="disabled" :aria-label="`${thread.starred ? '取消标星' : '标星'}：${thread.subject}`" :aria-pressed="Boolean(thread.starred)" @click="emit('star', thread, !thread.starred)">{{ thread.starred ? '已标星' : '标星' }}</button>
          <button type="button" :disabled="disabled" :aria-label="`归档：${thread.subject}`" @click="emit('archive', thread)">归档</button>
        </div>
      </li>
    </ul>
  </section>
</template>
