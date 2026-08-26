<script setup lang="ts">
import { computed } from 'vue'

export interface PhoenixTabItem {
  label: string
  value: string
  disabled?: boolean
  badge?: string | number
  panelId?: string
}

const props = withDefaults(
  defineProps<{
    modelValue: string
    items: PhoenixTabItem[]
    ariaLabel?: string
    stretch?: boolean
  }>(),
  {
    ariaLabel: '内容标签',
    stretch: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  change: [value: string]
}>()

const enabledItems = computed(() => props.items.filter((item) => !item.disabled))

function select(item: PhoenixTabItem) {
  if (item.disabled || item.value === props.modelValue) return
  emit('update:modelValue', item.value)
  emit('change', item.value)
}

function onKeydown(event: KeyboardEvent, item: PhoenixTabItem) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const items = enabledItems.value
  const currentIndex = Math.max(0, items.findIndex((entry) => entry.value === item.value))
  let nextIndex = currentIndex
  if (event.key === 'ArrowRight') nextIndex = (currentIndex + 1) % items.length
  if (event.key === 'ArrowLeft') nextIndex = (currentIndex - 1 + items.length) % items.length
  if (event.key === 'Home') nextIndex = 0
  if (event.key === 'End') nextIndex = items.length - 1
  const next = items[nextIndex]
  if (!next) return
  select(next)
  const buttons = (event.currentTarget as HTMLElement).parentElement?.querySelectorAll<HTMLElement>('[role="tab"]:not([disabled])')
  buttons?.[nextIndex]?.focus()
}
</script>

<template>
  <div class="px-tabs" :class="{ 'px-tabs--stretch': stretch }" role="tablist" :aria-label="ariaLabel">
    <button
      v-for="item in items"
      :key="item.value"
      type="button"
      role="tab"
      class="px-tabs__tab"
      :class="{ 'is-active': modelValue === item.value }"
      :aria-selected="modelValue === item.value"
      :aria-controls="item.panelId"
      :tabindex="modelValue === item.value ? 0 : -1"
      :disabled="item.disabled"
      @click="select(item)"
      @keydown="onKeydown($event, item)"
    >
      <span>{{ item.label }}</span>
      <span v-if="item.badge !== undefined" class="px-tabs__badge">{{ item.badge }}</span>
    </button>
  </div>
</template>
