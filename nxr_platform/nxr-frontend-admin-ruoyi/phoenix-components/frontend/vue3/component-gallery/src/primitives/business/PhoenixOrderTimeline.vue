<script setup lang="ts">
export interface PhoenixOrderEvent {
  id: string | number
  title: string
  time?: string
  content?: string
  status?: 'complete' | 'current' | 'pending' | 'error'
}

withDefaults(defineProps<{
  items: PhoenixOrderEvent[]
  title?: string
  orderNumber?: string
  emptyText?: string
}>(), {
  title: '订单进度', orderNumber: '', emptyText: '暂无订单记录',
})
</script>

<template>
  <section class="px-order-timeline" :aria-label="title">
    <header><h3>{{ title }}</h3><span v-if="orderNumber">{{ orderNumber }}</span></header>
    <ol v-if="items.length">
      <li v-for="item in items" :key="item.id" :class="`is-${item.status || 'pending'}`">
        <span class="px-order-timeline__dot" aria-hidden="true"></span>
        <div><strong>{{ item.title }}</strong><time v-if="item.time">{{ item.time }}</time><p v-if="item.content">{{ item.content }}</p></div>
      </li>
    </ol>
    <p v-else class="px-business-empty" role="status">{{ emptyText }}</p>
  </section>
</template>
