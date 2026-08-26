<script setup lang="ts">
import { computed } from 'vue'
import { finiteInteger, normalizeAppearance, safeImageUrl } from './safety'
import type { PhoenixContentAppearance } from './safety'

export type PhoenixMediaKind = 'image' | 'video'

export interface PhoenixMediaItem {
  id: string | number
  kind: PhoenixMediaKind
  title: string
  thumbnail?: string
  alt?: string
  duration?: string
  metadata?: string
  disabled?: boolean
}

const props = withDefaults(defineProps<{
  items: PhoenixMediaItem[]
  selectedId?: string | number
  appearance?: PhoenixContentAppearance
  title?: string
  columns?: number
  loading?: boolean
  disabled?: boolean
  emptyText?: string
}>(), {
  selectedId: undefined,
  appearance: 'modern',
  title: '媒体图库',
  columns: 3,
  loading: false,
  disabled: false,
  emptyText: '暂无媒体内容',
})

const emit = defineEmits<{
  'update:selectedId': [id: string | number]
  select: [item: PhoenixMediaItem]
  preview: [item: PhoenixMediaItem]
}>()

const appearanceValue = computed(() => normalizeAppearance(props.appearance))
const columnCount = computed(() => Math.min(6, finiteInteger(props.columns, 3, 1)))

function select(item: PhoenixMediaItem) {
  if (props.disabled || item.disabled) return
  emit('update:selectedId', item.id)
  emit('select', item)
}
</script>

<template>
  <section class="px-media-gallery px-content-card" :data-appearance="appearanceValue" :aria-busy="loading">
    <header class="px-content-header"><div><h3>{{ title }}</h3><span>{{ items.length }} 项媒体</span></div></header>
    <p v-if="loading" class="px-content-empty" role="status">媒体加载中</p>
    <p v-else-if="!items.length" class="px-content-empty" role="status">{{ emptyText }}</p>
    <ul v-else class="px-media-gallery__grid" :style="{ '--px-gallery-columns': String(columnCount) }" aria-label="媒体列表">
      <li v-for="item in items" :key="item.id" :class="{ 'is-selected': selectedId === item.id, 'is-disabled': item.disabled }">
        <button type="button" :disabled="disabled || item.disabled" :aria-pressed="selectedId === item.id" @click="select(item)" @dblclick="!disabled && !item.disabled && emit('preview', item)">
          <span class="px-media-gallery__visual">
            <img v-if="safeImageUrl(item.thumbnail)" :src="safeImageUrl(item.thumbnail)" :alt="item.alt || item.title" loading="lazy" />
            <span v-else class="px-media-gallery__placeholder" aria-hidden="true">{{ item.kind === 'video' ? '▶' : '图' }}</span>
            <span class="px-media-gallery__kind">{{ item.kind === 'video' ? '视频' : '图片' }}</span>
            <time v-if="item.duration">{{ item.duration }}</time>
          </span>
          <span class="px-media-gallery__copy"><strong>{{ item.title }}</strong><small v-if="item.metadata">{{ item.metadata }}</small></span>
        </button>
        <button class="px-media-gallery__preview" type="button" :disabled="disabled || item.disabled" :aria-label="`预览：${item.title}`" @click="emit('preview', item)">预览</button>
      </li>
    </ul>
  </section>
</template>
