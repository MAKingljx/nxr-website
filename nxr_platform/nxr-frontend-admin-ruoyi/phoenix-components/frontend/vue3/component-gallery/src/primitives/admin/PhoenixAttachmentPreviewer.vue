<script setup lang="ts">
import { computed } from 'vue'

export type PhoenixAttachmentKey = string | number
export interface PhoenixAttachment { id: PhoenixAttachmentKey; name: string; url: string; mimeType?: string; size?: number; downloadable?: boolean }
const props = withDefaults(defineProps<{
  attachments?: PhoenixAttachment[]
  selectedId?: PhoenixAttachmentKey | null
  allowedProtocols?: string[]
  allowedHosts?: string[]
  allowRelative?: boolean
  title?: string
  emptyText?: string
}>(), {
  attachments: () => [], selectedId: null, allowedProtocols: () => ['https:'], allowedHosts: () => [], allowRelative: false,
  title: '附件预览', emptyText: '暂无附件',
})
const emit = defineEmits<{
  'update:selectedId': [id: PhoenixAttachmentKey]
  select: [attachment: PhoenixAttachment]
  download: [attachment: PhoenixAttachment]
  blocked: [attachment: PhoenixAttachment, reason: string]
}>()
const selected = computed(() => props.attachments.find((item) => item.id === props.selectedId) ?? props.attachments[0])
const safePreview = computed(() => selected.value ? safeUrl(selected.value) : null)
const isImage = computed(() => Boolean(selected.value?.mimeType?.toLowerCase().match(/^image\/(png|jpe?g|gif|webp|avif)$/)))
const safeProtocols = new Set(['https:', 'http:', 'data:'])
function normalizedProtocols() {
  return new Set(props.allowedProtocols.map((item) => item.endsWith(':') ? item.toLowerCase() : `${item.toLowerCase()}:`).filter((item) => safeProtocols.has(item)))
}
function safeUrl(attachment: PhoenixAttachment) {
  const raw = attachment.url.trim()
  if (!raw || Array.from(raw).some((character) => character.charCodeAt(0) < 32)) return null
  if (raw.startsWith('/') || raw.startsWith('./') || raw.startsWith('../')) return props.allowRelative ? raw : null
  if (/^data:/i.test(raw)) {
    if (!normalizedProtocols().has('data:')) return null
    return /^data:image\/(png|jpe?g|gif|webp);base64,[a-z0-9+/=]+$/i.test(raw) ? raw : null
  }
  try {
    const url = new URL(raw)
    if (!normalizedProtocols().has(url.protocol.toLowerCase())) return null
    if (url.username || url.password) return null
    if (props.allowedHosts.length && !props.allowedHosts.some((host) => host.toLowerCase() === url.hostname.toLowerCase())) return null
    return url.href
  } catch { return null }
}
function choose(attachment: PhoenixAttachment) {
  emit('update:selectedId', attachment.id)
  emit('select', attachment)
  if (!safeUrl(attachment)) emit('blocked', attachment, '附件地址不在安全白名单内')
}
function formatSize(size?: number) {
  if (!Number.isFinite(size) || size! < 0) return '大小未知'
  if (size! < 1024) return `${Math.floor(size!)} B`
  if (size! < 1024 ** 2) return `${(size! / 1024).toFixed(1)} KB`
  return `${(size! / 1024 ** 2).toFixed(1)} MB`
}
</script>

<template>
  <section class="px-attachment-previewer" :aria-label="title">
    <header><h3>{{ title }}</h3><span>{{ attachments.length }} 个文件</span></header>
    <div v-if="attachments.length" class="px-attachment-previewer__layout">
      <ul aria-label="附件列表">
        <li v-for="attachment in attachments" :key="attachment.id"><button type="button" :class="{ 'is-selected': selected?.id === attachment.id }" :aria-current="selected?.id === attachment.id ? 'true' : undefined" @click="choose(attachment)"><strong>{{ attachment.name }}</strong><small>{{ formatSize(attachment.size) }}</small></button></li>
      </ul>
      <div class="px-attachment-previewer__preview" role="region" aria-label="预览内容">
        <template v-if="selected">
          <img v-if="safePreview && isImage" :src="safePreview" :alt="selected.name">
          <p v-else-if="safePreview">此文件不支持内嵌预览</p>
          <p v-else role="alert">附件地址已被安全策略拦截</p>
          <footer><strong>{{ selected.name }}</strong><button v-if="selected.downloadable !== false && safePreview" type="button" @click="emit('download', selected)">请求下载</button></footer>
        </template>
      </div>
    </div>
    <p v-else class="px-admin-state" role="status">{{ emptyText }}</p>
  </section>
</template>
