<script setup lang="ts">
import { computed } from 'vue'
import { clampInteger, clampNumber, formatCurrency } from './utils'

export interface PhoenixSeat {
  id: string | number
  label: string
  status?: 'available' | 'reserved' | 'disabled'
  price?: number
}
export interface PhoenixRoom {
  id: string | number
  name: string
  description?: string
  seats: PhoenixSeat[]
  disabled?: boolean
}
const props = withDefaults(defineProps<{
  roomId?: string | number | null
  modelValue: (string | number)[]
  rooms: PhoenixRoom[]
  maxSelected?: number
  currency?: string
  locale?: string
  disabled?: boolean
}>(), { roomId: null, maxSelected: 4, currency: 'CNY', locale: 'zh-CN', disabled: false })
const emit = defineEmits<{
  'update:roomId': [id: string | number]
  'update:modelValue': [ids: (string | number)[]]
  'room-change': [room: PhoenixRoom]
  change: [ids: (string | number)[], seats: PhoenixSeat[]]
}>()
const limit = computed(() => clampInteger(props.maxSelected, 1, 20))
const room = computed(() => props.rooms.find((item) => item.id === props.roomId) ?? props.rooms.find((item) => !item.disabled) ?? null)
const selectedIds = computed(() => {
  if (!room.value) return []
  const selectable = new Set(room.value.seats.filter((seat) => seat.status !== 'reserved' && seat.status !== 'disabled').map((seat) => seat.id))
  return [...new Set(props.modelValue)].filter((id) => selectable.has(id)).slice(0, limit.value)
})
function chooseRoom(next: PhoenixRoom) {
  if (props.disabled || next.disabled) return
  emit('update:roomId', next.id); emit('update:modelValue', []); emit('room-change', next); emit('change', [], [])
}
function chooseSeat(seat: PhoenixSeat) {
  if (props.disabled || !room.value || seat.status === 'reserved' || seat.status === 'disabled') return
  const selected = selectedIds.value.includes(seat.id)
  if (!selected && selectedIds.value.length >= limit.value) return
  const ids = selected ? selectedIds.value.filter((id) => id !== seat.id) : [...selectedIds.value, seat.id]
  emit('update:modelValue', ids)
  emit('change', ids, room.value.seats.filter((item) => ids.includes(item.id)))
}
</script>

<template>
  <section class="px-seat-room" aria-label="房间与座位选择">
    <div class="px-seat-room__rooms" role="tablist" aria-label="房间">
      <button v-for="item in rooms" :key="item.id" type="button" role="tab" :aria-selected="room?.id === item.id" :disabled="disabled || item.disabled" @click="chooseRoom(item)">{{ item.name }}</button>
    </div>
    <div v-if="room" class="px-seat-room__panel" role="tabpanel">
      <header><div><h3>{{ room.name }}</h3><span v-if="room.description">{{ room.description }}</span></div><strong>已选 {{ selectedIds.length }}/{{ limit }}</strong></header>
      <div class="px-seat-room__screen" aria-hidden="true">前方</div>
      <div class="px-seat-room__seats" role="group" aria-label="座位">
        <button v-for="seat in room.seats" :key="seat.id" type="button" :class="{ 'is-selected': selectedIds.includes(seat.id), 'is-reserved': seat.status === 'reserved' }" :aria-pressed="selectedIds.includes(seat.id)" :aria-label="`${seat.label}${seat.status === 'reserved' ? ' 已占用' : ''}`" :disabled="disabled || seat.status === 'reserved' || seat.status === 'disabled' || (!selectedIds.includes(seat.id) && selectedIds.length >= limit)" @click="chooseSeat(seat)"><span>{{ seat.label }}</span><small v-if="seat.price != null">{{ formatCurrency(clampNumber(seat.price, 0, 999999999), currency, locale) }}</small></button>
      </div>
    </div>
    <p v-else class="px-commerce-empty" role="status">暂无可用房间</p>
  </section>
</template>
