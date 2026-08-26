<script setup lang="ts">
import { useId } from 'vue'
import { clampInteger } from './utils'

export interface PhoenixTimeSlot {
  id: string | number
  label: string
  start?: string
  end?: string
  remaining?: number
  disabled?: boolean
}
const props = withDefaults(defineProps<{
  modelValue?: string | number | null
  slots: PhoenixTimeSlot[]
  title?: string
  disabled?: boolean
  emptyText?: string
}>(), { modelValue: null, title: '选择预约时段', disabled: false, emptyText: '暂无可预约时段' })
const emit = defineEmits<{
  'update:modelValue': [id: string | number]
  change: [slot: PhoenixTimeSlot]
}>()
const name = `phoenix-time-slot-${useId()}`
function remaining(slot: PhoenixTimeSlot) { return slot.remaining == null ? null : clampInteger(slot.remaining, 0, 999999) }
function unavailable(slot: PhoenixTimeSlot) { return props.disabled || slot.disabled || remaining(slot) === 0 }
function choose(slot: PhoenixTimeSlot) {
  if (unavailable(slot)) return
  emit('update:modelValue', slot.id); emit('change', slot)
}
</script>

<template>
  <fieldset class="px-time-slot-picker" :disabled="disabled">
    <legend>{{ title }}</legend>
    <p v-if="!slots.length" class="px-commerce-empty" role="status">{{ emptyText }}</p>
    <div v-else class="px-time-slot-picker__grid">
      <label v-for="slot in slots" :key="slot.id" :class="{ 'is-selected': modelValue === slot.id, 'is-disabled': unavailable(slot) }">
        <input type="radio" :name="name" :value="slot.id" :checked="modelValue === slot.id" :disabled="unavailable(slot)" @change="choose(slot)">
        <strong>{{ slot.label }}</strong><span v-if="slot.start || slot.end">{{ slot.start }}<template v-if="slot.start && slot.end">—</template>{{ slot.end }}</span><small v-if="remaining(slot) != null">{{ remaining(slot) ? `余 ${remaining(slot)} 个名额` : '已约满' }}</small>
      </label>
    </div>
  </fieldset>
</template>
