<script setup lang="ts">
type BookingState = 'pending' | 'confirmed' | 'cancelled' | 'completed'

withDefaults(defineProps<{
  title: string
  date: string
  time?: string
  location?: string
  participants?: number
  status?: BookingState
  editable?: boolean
  cancellable?: boolean
}>(), {
  time: '', location: '', participants: 1, status: 'pending', editable: false, cancellable: false,
})

defineEmits<{
  edit: []
  cancel: []
}>()

const labels: Record<BookingState, string> = {
  pending: '待确认', confirmed: '已确认', cancelled: '已取消', completed: '已完成',
}
</script>

<template>
  <article class="px-booking-summary" :class="`is-${status}`" :aria-label="`预约：${title}`">
    <header><span>{{ labels[status] }}</span><h3>{{ title }}</h3></header>
    <dl>
      <div><dt>日期</dt><dd>{{ date }}</dd></div>
      <div v-if="time"><dt>时间</dt><dd>{{ time }}</dd></div>
      <div v-if="location"><dt>地点</dt><dd>{{ location }}</dd></div>
      <div><dt>人数</dt><dd>{{ Math.max(1, Math.trunc(participants)) }} 人</dd></div>
    </dl>
    <footer v-if="editable || cancellable">
      <button v-if="editable" type="button" @click="$emit('edit')">修改预约</button>
      <button v-if="cancellable" class="is-danger" type="button" @click="$emit('cancel')">取消预约</button>
    </footer>
  </article>
</template>
