<script setup lang="ts">
import { nextTick, ref } from 'vue'

export type PhoenixKanbanValue = string | number
export interface PhoenixKanbanCard { id: PhoenixKanbanValue; title: string; disabled?: boolean; tag?: string }
export interface PhoenixKanbanColumn { id: PhoenixKanbanValue; title: string; cards: PhoenixKanbanCard[]; limit?: number }

const props = withDefaults(defineProps<{
  columns: PhoenixKanbanColumn[]
  selectedId?: PhoenixKanbanValue | null
  label?: string
  readonly?: boolean
}>(), {
  selectedId: null,
  label: '任务看板',
  readonly: false,
})

const emit = defineEmits<{
  'update:selectedId': [id: PhoenixKanbanValue]
  select: [card: PhoenixKanbanCard, column: PhoenixKanbanColumn]
  move: [card: PhoenixKanbanCard, fromColumnId: PhoenixKanbanValue, toColumnId: PhoenixKanbanValue, targetIndex: number]
}>()

const root = ref<HTMLElement>()
const dragging = ref<{ card: PhoenixKanbanCard; from: PhoenixKanbanValue }>()

function select(card: PhoenixKanbanCard, column: PhoenixKanbanColumn) {
  if (card.disabled) return
  emit('update:selectedId', card.id)
  emit('select', card, column)
}

function move(card: PhoenixKanbanCard, from: PhoenixKanbanColumn, direction: 1 | -1) {
  if (props.readonly || card.disabled) return
  const index = props.columns.findIndex((column) => column.id === from.id)
  const target = props.columns[index + direction]
  if (!target || (target.limit !== undefined && target.cards.length >= target.limit)) return
  emit('move', card, from.id, target.id, target.cards.length)
}

function focusRelative(columnIndex: number, cardIndex: number, direction: 1 | -1) {
  const column = props.columns[columnIndex]
  const next = Math.max(0, Math.min(column.cards.length - 1, cardIndex + direction))
  nextTick(() => root.value?.querySelector<HTMLElement>(`[data-column="${columnIndex}"][data-card="${next}"]`)?.focus())
}

function onKeydown(event: KeyboardEvent, card: PhoenixKanbanCard, column: PhoenixKanbanColumn, columnIndex: number, cardIndex: number) {
  if (event.key === 'ArrowLeft') move(card, column, -1)
  else if (event.key === 'ArrowRight') move(card, column, 1)
  else if (event.key === 'ArrowUp') focusRelative(columnIndex, cardIndex, -1)
  else if (event.key === 'ArrowDown') focusRelative(columnIndex, cardIndex, 1)
  else if (event.key === 'Enter' || event.key === ' ') select(card, column)
  else return
  event.preventDefault()
}

function drop(column: PhoenixKanbanColumn) {
  if (!dragging.value || props.readonly || dragging.value.from === column.id || (column.limit !== undefined && column.cards.length >= column.limit)) return
  emit('move', dragging.value.card, dragging.value.from, column.id, column.cards.length)
  dragging.value = undefined
}
</script>

<template>
  <section ref="root" class="px-kanban" :aria-label="label">
    <article v-for="(column, columnIndex) in columns" :key="column.id" class="px-kanban__column" @dragover.prevent @drop="drop(column)">
      <header><h3>{{ column.title }}</h3><span>{{ column.cards.length }}<template v-if="column.limit !== undefined">/{{ column.limit }}</template></span></header>
      <div class="px-kanban__cards" role="list">
        <button v-for="(card, cardIndex) in column.cards" :key="card.id" type="button" role="listitem" class="px-kanban__card" :class="{ 'is-selected': selectedId === card.id }" :disabled="card.disabled" :draggable="!readonly && !card.disabled" :data-column="columnIndex" :data-card="cardIndex" :aria-current="selectedId === card.id ? 'true' : undefined" @click="select(card, column)" @keydown="onKeydown($event, card, column, columnIndex, cardIndex)" @dragstart="dragging = { card, from: column.id }" @dragend="dragging = undefined">
          <strong>{{ card.title }}</strong><span v-if="card.tag">{{ card.tag }}</span><slot name="card" :card="card" :column="column" />
        </button>
        <p v-if="!column.cards.length" class="px-kanban__empty">暂无任务</p>
      </div>
    </article>
    <p v-if="!columns.length" class="px-kanban__empty is-board">暂无看板</p>
  </section>
</template>
