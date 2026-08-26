<script setup lang="ts">
import { nextTick, ref } from 'vue'

export type PhoenixDropdownValue = string | number
export interface PhoenixDropdownItem {
  label: string
  value: PhoenixDropdownValue
  disabled?: boolean
  danger?: boolean
}

const props = withDefaults(defineProps<{
  modelValue?: boolean
  items: PhoenixDropdownItem[]
  label?: string
  placement?: 'left' | 'right'
  disabled?: boolean
}>(), {
  modelValue: false,
  label: '更多操作',
  placement: 'left',
  disabled: false,
})

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  select: [value: PhoenixDropdownValue, item: PhoenixDropdownItem]
}>()

const root = ref<HTMLElement>()
const activeIndex = ref(-1)

function setOpen(open: boolean, focusFirst = false) {
  if (props.disabled || props.modelValue === open) return
  emit('update:modelValue', open)
  if (open && focusFirst) nextTick(() => root.value?.querySelector<HTMLElement>('[role="menuitem"]:not(:disabled)')?.focus())
}

function select(item: PhoenixDropdownItem) {
  if (item.disabled) return
  emit('select', item.value, item)
  emit('update:modelValue', false)
  nextTick(() => root.value?.querySelector<HTMLElement>('.px-dropdown__trigger')?.focus())
}

function move(index: number, direction: 1 | -1) {
  let next = index
  do next = (next + direction + props.items.length) % props.items.length
  while (props.items[next]?.disabled && next !== index)
  activeIndex.value = next
  nextTick(() => root.value?.querySelectorAll<HTMLElement>('[role="menuitem"]')[next]?.focus())
}

function onTriggerKeydown(event: KeyboardEvent) {
  if (event.key === 'ArrowDown' || event.key === 'Enter' || event.key === ' ') {
    setOpen(true, true)
    event.preventDefault()
  } else if (event.key === 'Escape') setOpen(false)
}

function onItemKeydown(event: KeyboardEvent, item: PhoenixDropdownItem, index: number) {
  if (event.key === 'ArrowDown') move(index, 1)
  else if (event.key === 'ArrowUp') move(index, -1)
  else if (event.key === 'Home') move(props.items.length - 1, 1)
  else if (event.key === 'End') move(0, -1)
  else if (event.key === 'Escape') {
    setOpen(false)
    nextTick(() => root.value?.querySelector<HTMLElement>('.px-dropdown__trigger')?.focus())
  } else if (event.key === 'Enter' || event.key === ' ') select(item)
  else return
  event.preventDefault()
}

function onFocusout(event: FocusEvent) {
  if (!root.value?.contains(event.relatedTarget as Node | null)) setOpen(false)
}
</script>

<template>
  <div ref="root" class="px-dropdown" @focusout="onFocusout">
    <button type="button" class="px-dropdown__trigger" :disabled="disabled" :aria-expanded="modelValue" aria-haspopup="menu" @click="setOpen(!modelValue)" @keydown="onTriggerKeydown">
      <slot>{{ label }}</slot><span aria-hidden="true">⌄</span>
    </button>
    <div v-if="modelValue" role="menu" class="px-dropdown__menu" :class="`px-dropdown__menu--${placement}`" :aria-label="label">
      <button v-for="(item, index) in items" :key="item.value" type="button" role="menuitem" :disabled="item.disabled" :class="{ 'is-danger': item.danger }" :tabindex="activeIndex === index ? 0 : -1" @focus="activeIndex = index" @keydown="onItemKeydown($event, item, index)" @click="select(item)">{{ item.label }}</button>
      <p v-if="!items.length" class="px-dropdown__empty">暂无操作</p>
    </div>
  </div>
</template>
