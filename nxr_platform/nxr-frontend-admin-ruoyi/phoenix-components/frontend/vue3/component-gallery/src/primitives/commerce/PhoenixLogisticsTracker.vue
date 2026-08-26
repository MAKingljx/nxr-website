<script setup lang="ts">
export interface PhoenixLogisticsEvent {
  id: string | number
  title: string
  time?: string
  location?: string
  description?: string
  status?: 'complete' | 'current' | 'pending' | 'exception'
}
withDefaults(defineProps<{
  events: PhoenixLogisticsEvent[]
  trackingNumber?: string
  carrier?: string
  title?: string
  emptyText?: string
}>(), { trackingNumber: '', carrier: '', title: '物流进度', emptyText: '暂无物流信息' })
const emit = defineEmits<{ copy: [trackingNumber: string]; refresh: [] }>()
</script>

<template>
  <section class="px-logistics-tracker" :aria-label="title">
    <header><div><h3>{{ title }}</h3><span v-if="carrier">{{ carrier }}</span></div><button type="button" @click="emit('refresh')">刷新状态</button></header>
    <div v-if="trackingNumber" class="px-logistics-tracker__number"><span>运单号 {{ trackingNumber }}</span><button type="button" aria-label="请求复制运单号" @click="emit('copy', trackingNumber)">复制</button></div>
    <p v-if="!events.length" class="px-commerce-empty" role="status">{{ emptyText }}</p>
    <ol v-else>
      <li v-for="event in events" :key="event.id" :class="`is-${event.status || 'pending'}`">
        <i aria-hidden="true"></i><div><strong>{{ event.title }}</strong><time v-if="event.time">{{ event.time }}</time><span v-if="event.location">{{ event.location }}</span><p v-if="event.description">{{ event.description }}</p></div>
      </li>
    </ol>
  </section>
</template>
