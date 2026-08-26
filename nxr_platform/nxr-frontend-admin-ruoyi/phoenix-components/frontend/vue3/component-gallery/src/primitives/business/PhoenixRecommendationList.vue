<script setup lang="ts">
import { computed } from 'vue'

export interface PhoenixRecommendationItem {
  id: string | number
  title: string
  summary?: string
  badge?: string
  score?: number
  image?: string
  disabled?: boolean
}

const props = withDefaults(defineProps<{
  items: PhoenixRecommendationItem[]
  title?: string
  maxItems?: number
  showRefresh?: boolean
  refreshing?: boolean
  emptyText?: string
}>(), {
  title: '为你推荐', maxItems: 6, showRefresh: false, refreshing: false, emptyText: '暂无推荐内容',
})

const emit = defineEmits<{
  select: [item: PhoenixRecommendationItem, index: number]
  refresh: []
}>()

const visible = computed(() => props.items.slice(0, Math.max(0, Math.trunc(props.maxItems))))
function score(value?: number) {
  return typeof value === 'number' && Number.isFinite(value) ? Math.min(5, Math.max(0, value)).toFixed(1) : ''
}
function safeImage(value?: string) {
  if (!value) return ''
  return /^(https?:\/\/|\/|\.\/|\.\.\/|blob:|data:image\/(?!svg\+xml)[a-z0-9.+-]+;base64,)/i.test(value.trim()) ? value.trim() : ''
}
</script>

<template>
  <section class="px-recommendation-list" :aria-label="title" :aria-busy="refreshing">
    <header><h3>{{ title }}</h3><button v-if="showRefresh" type="button" :disabled="refreshing" @click="emit('refresh')">{{ refreshing ? '更新中' : '换一批' }}</button></header>
    <p v-if="!visible.length" class="px-business-empty" role="status">{{ emptyText }}</p>
    <ul v-else>
      <li v-for="(item, index) in visible" :key="item.id">
        <button type="button" :disabled="item.disabled" @click="emit('select', item, index)">
          <span class="px-recommendation-list__media"><img v-if="safeImage(item.image)" :src="safeImage(item.image)" :alt="item.title"><span v-else aria-hidden="true">{{ item.title.slice(0, 1) }}</span></span>
          <span class="px-recommendation-list__content"><strong>{{ item.title }}</strong><span v-if="item.summary">{{ item.summary }}</span></span>
          <span v-if="item.badge" class="px-recommendation-list__badge">{{ item.badge }}</span>
          <span v-if="score(item.score)" class="px-recommendation-list__score" :aria-label="`评分 ${score(item.score)}`">★ {{ score(item.score) }}</span>
        </button>
      </li>
    </ul>
  </section>
</template>
