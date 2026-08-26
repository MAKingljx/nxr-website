<script setup lang="ts">
import { computed } from 'vue'

interface CalendarCell { date: string; day: number; current: boolean; today: boolean }

const props = withDefaults(defineProps<{
  modelValue?: string
  viewDate?: string
  min?: string
  max?: string
  weekStartsOn?: 0 | 1
  label?: string
}>(), {
  modelValue: '',
  viewDate: '',
  min: '',
  max: '',
  weekStartsOn: 1,
  label: '日期选择',
})

const emit = defineEmits<{
  'update:modelValue': [date: string]
  'update:viewDate': [date: string]
  select: [date: string]
}>()

function parse(value: string) {
  const matched = /^(\d{4})-(\d{2})(?:-(\d{2}))?$/.exec(value)
  if (!matched) return null
  const year = Number(matched[1]); const month = Number(matched[2]); const day = Number(matched[3] || 1)
  if (month < 1 || month > 12) return null
  const result = new Date(year, month - 1, day)
  return result.getFullYear() === year && result.getMonth() === month - 1 && result.getDate() === day ? result : null
}

function format(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

const selected = computed(() => parse(props.modelValue))
const view = computed(() => parse(props.viewDate) || selected.value || new Date())
const title = computed(() => `${view.value.getFullYear()}年${view.value.getMonth() + 1}月`)
const weekdays = computed(() => props.weekStartsOn === 1 ? ['一', '二', '三', '四', '五', '六', '日'] : ['日', '一', '二', '三', '四', '五', '六'])
const today = format(new Date())
const cells = computed<CalendarCell[]>(() => {
  const first = new Date(view.value.getFullYear(), view.value.getMonth(), 1)
  const offset = (first.getDay() - props.weekStartsOn + 7) % 7
  return Array.from({ length: 42 }, (_, index) => {
    const date = new Date(first.getFullYear(), first.getMonth(), index - offset + 1)
    const value = format(date)
    return { date: value, day: date.getDate(), current: date.getMonth() === first.getMonth(), today: value === today }
  })
})

function disabled(date: string) { return Boolean((props.min && date < props.min) || (props.max && date > props.max)) }
function select(date: string) { if (!disabled(date)) { emit('update:modelValue', date); emit('select', date) } }
function navigate(offset: number) {
  const next = new Date(view.value.getFullYear(), view.value.getMonth() + offset, 1)
  emit('update:viewDate', format(next))
}
</script>

<template>
  <section class="px-calendar" :aria-label="label">
    <header><button type="button" aria-label="上个月" @click="navigate(-1)">‹</button><strong aria-live="polite">{{ title }}</strong><button type="button" aria-label="下个月" @click="navigate(1)">›</button></header>
    <div class="px-calendar__week" aria-hidden="true"><span v-for="day in weekdays" :key="day">{{ day }}</span></div>
    <div class="px-calendar__grid" role="grid" :aria-label="title">
      <button v-for="cell in cells" :key="cell.date" type="button" role="gridcell" :disabled="disabled(cell.date)" :class="{ 'is-outside': !cell.current, 'is-selected': modelValue === cell.date, 'is-today': cell.today }" :aria-selected="modelValue === cell.date" :aria-label="cell.date" @click="select(cell.date)"><slot name="day" :cell="cell">{{ cell.day }}</slot></button>
    </div>
  </section>
</template>
