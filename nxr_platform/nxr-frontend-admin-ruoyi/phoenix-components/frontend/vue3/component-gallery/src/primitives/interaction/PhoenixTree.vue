<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'

export type PhoenixTreeValue = string | number

export interface PhoenixTreeNode {
  label: string
  value: PhoenixTreeValue
  disabled?: boolean
  children?: PhoenixTreeNode[]
}

interface FlatTreeNode {
  node: PhoenixTreeNode
  level: number
  parentValue?: PhoenixTreeValue
}

const props = withDefaults(
  defineProps<{
    nodes: PhoenixTreeNode[]
    modelValue: PhoenixTreeValue | null
    expandedValues?: PhoenixTreeValue[]
    label?: string
    disabled?: boolean
  }>(),
  {
    expandedValues: () => [],
    label: '树形选项',
    disabled: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: PhoenixTreeValue]
  'update:expandedValues': [values: PhoenixTreeValue[]]
  change: [value: PhoenixTreeValue, node: PhoenixTreeNode]
  'expand-change': [node: PhoenixTreeNode, expanded: boolean]
}>()

const focusedIndex = ref(-1)

const visibleNodes = computed<FlatTreeNode[]>(() => {
  const result: FlatTreeNode[] = []
  const walk = (nodes: PhoenixTreeNode[], level: number, parentValue?: PhoenixTreeValue) => {
    nodes.forEach((node) => {
      result.push({ node, level, parentValue })
      if (node.children?.length && props.expandedValues.includes(node.value)) {
        walk(node.children, level + 1, node.value)
      }
    })
  }
  walk(props.nodes, 1)
  return result
})

function isExpanded(node: PhoenixTreeNode) {
  return props.expandedValues.includes(node.value)
}

function toggle(node: PhoenixTreeNode) {
  if (props.disabled || node.disabled || !node.children?.length) return
  const expanded = !isExpanded(node)
  const values = expanded
    ? [...props.expandedValues, node.value]
    : props.expandedValues.filter((value) => value !== node.value)
  emit('update:expandedValues', values)
  emit('expand-change', node, expanded)
}

function select(node: PhoenixTreeNode) {
  if (props.disabled || node.disabled || props.modelValue === node.value) return
  emit('update:modelValue', node.value)
  emit('change', node.value, node)
}

function focusAt(index: number, currentTarget?: HTMLElement) {
  const lastIndex = visibleNodes.value.length - 1
  focusedIndex.value = Math.min(lastIndex, Math.max(0, index))
  nextTick(() => {
    const tree = currentTarget?.closest('[role="tree"]')
    tree?.querySelectorAll<HTMLElement>('[role="treeitem"]')[focusedIndex.value]?.focus()
  })
}

function onKeydown(event: KeyboardEvent, item: FlatTreeNode, index: number) {
  const node = item.node
  if (event.key === 'ArrowDown') focusAt(index + 1, event.currentTarget as HTMLElement)
  else if (event.key === 'ArrowUp') focusAt(index - 1, event.currentTarget as HTMLElement)
  else if (event.key === 'Home') focusAt(0, event.currentTarget as HTMLElement)
  else if (event.key === 'End') focusAt(visibleNodes.value.length - 1, event.currentTarget as HTMLElement)
  else if (event.key === 'ArrowRight' && node.children?.length) {
    if (!isExpanded(node)) toggle(node)
    else focusAt(index + 1, event.currentTarget as HTMLElement)
  } else if (event.key === 'ArrowLeft') {
    if (node.children?.length && isExpanded(node)) toggle(node)
    else if (item.parentValue !== undefined) {
      focusAt(visibleNodes.value.findIndex((entry) => entry.node.value === item.parentValue), event.currentTarget as HTMLElement)
    }
  } else if (event.key === 'Enter' || event.key === ' ') select(node)
  else return
  event.preventDefault()
}
</script>

<template>
  <div class="px-tree" role="tree" :aria-label="label" :aria-disabled="disabled">
    <div
      v-for="(item, index) in visibleNodes"
      :key="item.node.value"
      role="treeitem"
      class="px-tree__item"
      :class="{ 'is-selected': modelValue === item.node.value, 'is-disabled': disabled || item.node.disabled }"
      :style="{ '--px-tree-level': item.level }"
      :aria-level="item.level"
      :aria-selected="modelValue === item.node.value"
      :aria-expanded="item.node.children?.length ? isExpanded(item.node) : undefined"
      :aria-disabled="disabled || item.node.disabled"
      :tabindex="focusedIndex === index || (focusedIndex < 0 && (modelValue === item.node.value || index === 0)) ? 0 : -1"
      @focus="focusedIndex = index"
      @keydown="onKeydown($event, item, index)"
      @click="select(item.node)"
    >
      <button
        v-if="item.node.children?.length"
        type="button"
        class="px-tree__toggle"
        :disabled="disabled || item.node.disabled"
        :aria-label="`${isExpanded(item.node) ? '收起' : '展开'}${item.node.label}`"
        :tabindex="-1"
        @click.stop="toggle(item.node)"
      >
        <span aria-hidden="true">›</span>
      </button>
      <span v-else class="px-tree__spacer" aria-hidden="true"></span>
      <span>{{ item.node.label }}</span>
    </div>
    <p v-if="!visibleNodes.length" class="px-tree__empty">暂无数据</p>
  </div>
</template>
