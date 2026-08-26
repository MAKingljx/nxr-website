<script setup lang="ts">
import { computed } from 'vue'
import { formatFileSize, normalizeAppearance, safeResourceUrl } from './safety'
import type { PhoenixContentAppearance } from './safety'

export interface PhoenixDocumentInfo {
  id: string | number
  name: string
  size?: number
  mimeType?: string
  extension?: string
  pages?: number
  owner?: string
  updatedAt?: string
  url?: string
}

const props = withDefaults(defineProps<{
  document?: PhoenixDocumentInfo
  appearance?: PhoenixContentAppearance
  title?: string
  loading?: boolean
  disabled?: boolean
  downloadable?: boolean
  emptyText?: string
}>(), {
  document: undefined,
  appearance: 'modern',
  title: '文档预览',
  loading: false,
  disabled: false,
  downloadable: false,
  emptyText: '暂无文档信息',
})

const emit = defineEmits<{
  open: [document: PhoenixDocumentInfo, safeUrl: string]
  download: [document: PhoenixDocumentInfo, safeUrl: string]
}>()

const appearanceValue = computed(() => normalizeAppearance(props.appearance))
const safeUrl = computed(() => safeResourceUrl(props.document?.url))
const extension = computed(() => {
  if (!props.document) return '文件'
  const fromName = props.document.name.includes('.') ? props.document.name.split('.').pop() : ''
  return (props.document.extension || fromName || '文件').replace(/^\./, '').slice(0, 8).toUpperCase()
})
</script>

<template>
  <section class="px-document-preview px-content-card" :data-appearance="appearanceValue" :aria-busy="loading">
    <header class="px-content-header"><div><h3>{{ title }}</h3><span>仅展示文档信息，不解析或执行内容</span></div></header>
    <p v-if="loading" class="px-content-empty" role="status">文档信息加载中</p>
    <p v-else-if="!document" class="px-content-empty" role="status">{{ emptyText }}</p>
    <article v-else>
      <span class="px-document-preview__icon" aria-hidden="true">{{ extension }}</span>
      <div class="px-document-preview__copy">
        <h4>{{ document.name }}</h4>
        <dl>
          <div><dt>大小</dt><dd>{{ formatFileSize(document.size) }}</dd></div>
          <div v-if="document.mimeType"><dt>类型</dt><dd>{{ document.mimeType }}</dd></div>
          <div v-if="document.pages !== undefined"><dt>页数</dt><dd>{{ Number.isFinite(document.pages) ? Math.max(0, Math.trunc(document.pages)) : 0 }} 页</dd></div>
          <div v-if="document.owner"><dt>所有者</dt><dd>{{ document.owner }}</dd></div>
          <div v-if="document.updatedAt"><dt>更新时间</dt><dd>{{ document.updatedAt }}</dd></div>
        </dl>
      </div>
      <div class="px-content-actions">
        <button type="button" :disabled="disabled || !safeUrl" @click="emit('open', document, safeUrl)">打开文档</button>
        <button v-if="downloadable" type="button" :disabled="disabled || !safeUrl" @click="emit('download', document, safeUrl)">请求下载</button>
      </div>
    </article>
  </section>
</template>
