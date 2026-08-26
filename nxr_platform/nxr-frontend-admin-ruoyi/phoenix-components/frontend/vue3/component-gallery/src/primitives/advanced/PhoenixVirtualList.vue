<script setup lang="ts">
import { computed, ref, watch } from 'vue'

export interface PhoenixVirtualListItem {
  [key: string]: unknown
}

const props = withDefaults(
  defineProps<{
    items: PhoenixVirtualListItem[]
    itemHeight?: number
    height?: number
    overscan?: number
    itemKey?: string
    ariaLabel?: string
    emptyText?: string
  }>(),
  {
    itemHeight: 48,
    height: 320,
    overscan: 4,
    itemKey: 'id',
    ariaLabel: '虚拟列表',
    emptyText: '暂无列表内容',
  },
)

const emit = defineEmits<{
  'range-change': [start: number, end: number]
  scroll: [scrollTop: number]
}>()

const viewport = ref<HTMLElement>()
const scrollTop = ref(0)
const safeItemHeight = computed(() => Math.max(1, props.itemHeight))
const safeHeight = computed(() => Math.max(safeItemHeight.value, props.height))
const visibleCount = computed(() => Math.ceil(safeHeight.value / safeItemHeight.value))
const startIndex = computed(() => Math.max(0, Math.floor(scrollTop.value / safeItemHeight.value) - Math.max(0, props.overscan)))
const endIndex = computed(() => Math.min(props.items.length, startIndex.value + visibleCount.value + Math.max(0, props.overscan) * 2))
const visibleItems = computed(() =>
  props.items.slice(startIndex.value, endIndex.value).map((item, offset) => ({
    item,
    index: startIndex.value + offset,
  })),
)
const totalHeight = computed(() => props.items.length * safeItemHeight.value)
const offset = computed(() => startIndex.value * safeItemHeight.value)

watch(
  () => props.items.length,
  () => setScrollTop(scrollTop.value),
)

function resolveKey(item: PhoenixVirtualListItem, index: number) {
  const value = item[props.itemKey]
  return typeof value === 'string' || typeof value === 'number' ? value : index
}

function fallbackLabel(item: PhoenixVirtualListItem) {
  const value = item.label ?? item.name ?? item.title
  return typeof value === 'string' || typeof value === 'number' ? String(value) : `列表项 ${props.items.indexOf(item) + 1}`
}

function setScrollTop(value: number) {
  const maximum = Math.max(0, totalHeight.value - safeHeight.value)
  const next = Math.min(maximum, Math.max(0, value))
  scrollTop.value = next
  if (viewport.value) viewport.value.scrollTop = next
}

function onScroll(event: Event) {
  scrollTop.value = (event.currentTarget as HTMLElement).scrollTop
  emit('scroll', scrollTop.value)
  emit('range-change', startIndex.value, endIndex.value)
}

function onKeydown(event: KeyboardEvent) {
  if (!['Home', 'End', 'PageUp', 'PageDown'].includes(event.key)) return
  event.preventDefault()
  if (event.key === 'Home') setScrollTop(0)
  if (event.key === 'End') setScrollTop(totalHeight.value)
  if (event.key === 'PageUp') setScrollTop(scrollTop.value - safeHeight.value)
  if (event.key === 'PageDown') setScrollTop(scrollTop.value + safeHeight.value)
}

function scrollToIndex(index: number) {
  const safeIndex = Math.min(Math.max(0, index), Math.max(0, props.items.length - 1))
  setScrollTop(safeIndex * safeItemHeight.value)
}

defineExpose({ scrollToIndex })
</script>

<template>
  <div
    ref="viewport"
    class="px-virtual-list"
    role="list"
    tabindex="0"
    :aria-label="ariaLabel"
    :style="{ height: `${safeHeight}px` }"
    @scroll="onScroll"
    @keydown="onKeydown"
  >
    <div v-if="items.length === 0" class="px-virtual-list__empty" role="status">{{ emptyText }}</div>
    <div v-else class="px-virtual-list__spacer" :style="{ height: `${totalHeight}px` }">
      <div class="px-virtual-list__window" :style="{ transform: `translateY(${offset}px)` }">
        <div
          v-for="entry in visibleItems"
          :key="resolveKey(entry.item, entry.index)"
          class="px-virtual-list__item"
          role="listitem"
          :aria-posinset="entry.index + 1"
          :aria-setsize="items.length"
          :style="{ height: `${safeItemHeight}px` }"
        >
          <slot :item="entry.item" :index="entry.index">{{ fallbackLabel(entry.item) }}</slot>
        </div>
      </div>
    </div>
  </div>
</template>
