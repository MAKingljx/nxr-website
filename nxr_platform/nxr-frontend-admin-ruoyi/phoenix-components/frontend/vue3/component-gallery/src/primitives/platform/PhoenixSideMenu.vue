<script setup lang="ts">
import { nextTick, ref } from 'vue'

export type PhoenixSideMenuValue = string | number
export interface PhoenixSideMenuItem {
  label: string
  value: PhoenixSideMenuValue
  icon?: string
  disabled?: boolean
  badge?: string | number
}

const props = withDefaults(defineProps<{
  items: PhoenixSideMenuItem[]
  modelValue: PhoenixSideMenuValue | null
  label?: string
  collapsed?: boolean
}>(), {
  label: '主导航',
  collapsed: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: PhoenixSideMenuValue]
  change: [value: PhoenixSideMenuValue, item: PhoenixSideMenuItem]
}>()

const focusedIndex = ref(-1)

function initialFocusableIndex() {
  const selected = props.items.findIndex((item) => item.value === props.modelValue && !item.disabled)
  return selected >= 0 ? selected : props.items.findIndex((item) => !item.disabled)
}

function select(item: PhoenixSideMenuItem) {
  if (item.disabled) return
  emit('update:modelValue', item.value)
  emit('change', item.value, item)
}

function focusAt(index: number, target: HTMLElement) {
  const enabled = props.items.map((item, itemIndex) => ({ item, itemIndex })).filter(({ item }) => !item.disabled)
  if (!enabled.length) return
  const current = enabled.findIndex(({ itemIndex }) => itemIndex === index)
  const next = enabled[Math.max(0, Math.min(enabled.length - 1, current))] || enabled[0]
  focusedIndex.value = next.itemIndex
  nextTick(() => target.closest('[role="navigation"]')?.querySelectorAll<HTMLElement>('[role="menuitem"]')[next.itemIndex]?.focus())
}

function move(index: number, direction: 1 | -1, target: HTMLElement) {
  let next = index
  do next = (next + direction + props.items.length) % props.items.length
  while (props.items[next]?.disabled && next !== index)
  focusAt(next, target)
}

function lastEnabledIndex() {
  for (let index = props.items.length - 1; index >= 0; index -= 1) {
    if (!props.items[index]?.disabled) return index
  }
  return -1
}

function onKeydown(event: KeyboardEvent, item: PhoenixSideMenuItem, index: number) {
  const target = event.currentTarget as HTMLElement
  if (event.key === 'ArrowDown') move(index, 1, target)
  else if (event.key === 'ArrowUp') move(index, -1, target)
  else if (event.key === 'Home') focusAt(props.items.findIndex((entry) => !entry.disabled), target)
  else if (event.key === 'End') focusAt(lastEnabledIndex(), target)
  else if (event.key === 'Enter' || event.key === ' ') select(item)
  else return
  event.preventDefault()
}
</script>

<template>
  <nav class="px-side-menu" :class="{ 'is-collapsed': collapsed }" :aria-label="label" role="navigation">
    <ul role="menu">
      <li v-for="(item, index) in items" :key="item.value" role="none">
        <button
          type="button"
          role="menuitem"
          :class="{ 'is-active': modelValue === item.value }"
          :disabled="item.disabled"
          :aria-current="modelValue === item.value ? 'page' : undefined"
          :aria-label="collapsed ? item.label : undefined"
          :tabindex="focusedIndex === index || (focusedIndex < 0 && index === initialFocusableIndex()) ? 0 : -1"
          @focus="focusedIndex = index"
          @keydown="onKeydown($event, item, index)"
          @click="select(item)"
        >
          <span v-if="item.icon" class="px-side-menu__icon" aria-hidden="true">{{ item.icon }}</span>
          <span class="px-side-menu__label">{{ item.label }}</span>
          <span v-if="item.badge !== undefined" class="px-side-menu__badge">{{ item.badge }}</span>
        </button>
      </li>
    </ul>
  </nav>
</template>
