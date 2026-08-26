<script setup lang="ts">
export interface PhoenixTimelineItem {
  title: string
  time?: string
  content?: string
  status?: 'default' | 'success' | 'warning' | 'danger'
}

withDefaults(defineProps<{
  items: PhoenixTimelineItem[]
  label?: string
  reverse?: boolean
}>(), {
  label: '事件时间线',
  reverse: false,
})
</script>

<template>
  <ol class="px-timeline" :class="{ 'is-reverse': reverse }" :aria-label="label">
    <li v-for="(item, index) in (reverse ? [...items].reverse() : items)" :key="`${item.title}-${item.time || index}`" :class="`px-timeline__item--${item.status || 'default'}`">
      <span class="px-timeline__dot" aria-hidden="true"></span>
      <div class="px-timeline__content">
        <div><strong>{{ item.title }}</strong><time v-if="item.time">{{ item.time }}</time></div>
        <p v-if="item.content">{{ item.content }}</p>
        <slot :name="`item-${index}`" :item="item" />
      </div>
    </li>
    <li v-if="!items.length" class="px-timeline__empty">暂无记录</li>
  </ol>
</template>
