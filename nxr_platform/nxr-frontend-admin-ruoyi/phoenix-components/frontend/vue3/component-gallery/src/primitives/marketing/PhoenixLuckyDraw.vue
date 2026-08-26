<script setup lang="ts">
import { computed } from 'vue'

export interface PhoenixLuckyDrawItem {
  id: string | number
  label: string
  image?: string
  disabled?: boolean
}

export type PhoenixMarketingAppearance = 'modern' | 'festive' | 'minimal'

const props = withDefaults(defineProps<{
  items: PhoenixLuckyDrawItem[]
  selectedId?: string | number | null
  appearance?: PhoenixMarketingAppearance
  layout?: 'wheel' | 'grid'
  running?: boolean
  disabled?: boolean
  title?: string
  actionLabel?: string
  runningLabel?: string
  emptyText?: string
}>(), {
  selectedId: null,
  appearance: 'modern',
  layout: 'wheel',
  running: false,
  disabled: false,
  title: '幸运抽奖',
  actionLabel: '立即抽奖',
  runningLabel: '正在抽奖',
  emptyText: '暂无奖项',
})

const emit = defineEmits<{
  start: []
  select: [item: PhoenixLuckyDrawItem, index: number]
}>()

const visibleItems = computed(() => props.items.slice(0, 12))
const selectedItem = computed(() => visibleItems.value.find((item) => item.id === props.selectedId))
const unavailable = computed(() => props.disabled || props.running || !visibleItems.value.some((item) => !item.disabled))

function safeImage(value?: string) {
  if (!value) return ''
  const normalized = value.trim()
  return /^(https?:\/\/|\/|\.\/|\.\.\/|blob:|data:image\/(?!svg\+xml)[a-z0-9.+-]+;base64,)/i.test(normalized)
    ? normalized
    : ''
}

function itemStyle(index: number) {
  const total = Math.max(1, visibleItems.value.length)
  return { '--px-lucky-angle': `${(index * 360) / total}deg` }
}

function selectItem(item: PhoenixLuckyDrawItem, index: number) {
  if (!item.disabled && !props.running) emit('select', item, index)
}
</script>

<template>
  <section
    class="px-lucky-draw"
    :class="`px-lucky-draw--${layout}`"
    :data-appearance="appearance"
    :aria-label="title"
    :aria-busy="running"
  >
    <header><h3>{{ title }}</h3><strong v-if="selectedItem">已中奖：{{ selectedItem.label }}</strong></header>
    <p v-if="!visibleItems.length" class="px-marketing-empty" role="status">{{ emptyText }}</p>
    <div v-else class="px-lucky-draw__stage">
      <ul :style="{ '--px-lucky-count': visibleItems.length }">
        <li v-for="(item, index) in visibleItems" :key="item.id" :style="itemStyle(index)" :class="{ 'is-selected': item.id === selectedId }">
          <button type="button" :disabled="item.disabled || running" :aria-pressed="item.id === selectedId" @click="selectItem(item, index)">
            <img v-if="safeImage(item.image)" :src="safeImage(item.image)" :alt="item.label">
            <span v-else aria-hidden="true">{{ item.label.slice(0, 1) }}</span>
            <strong>{{ item.label }}</strong>
          </button>
        </li>
      </ul>
      <button class="px-lucky-draw__action" type="button" :disabled="unavailable" @click="emit('start')">
        {{ running ? runningLabel : actionLabel }}
      </button>
    </div>
  </section>
</template>
