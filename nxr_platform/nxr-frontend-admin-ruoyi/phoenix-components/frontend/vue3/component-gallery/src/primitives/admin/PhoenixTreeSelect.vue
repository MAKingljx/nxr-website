<script setup lang="ts">
import { computed } from 'vue'

export type PhoenixAdminTreeValue = string | number
export interface PhoenixAdminTreeNode { value: PhoenixAdminTreeValue; label: string; children?: PhoenixAdminTreeNode[]; disabled?: boolean }
interface FlatNode { node: PhoenixAdminTreeNode; level: number; parent: PhoenixAdminTreeValue | null }
const props = withDefaults(defineProps<{
  nodes?: PhoenixAdminTreeNode[]
  modelValue?: PhoenixAdminTreeValue[]
  expandedKeys?: PhoenixAdminTreeValue[]
  multiple?: boolean
  disabled?: boolean
  label?: string
  emptyText?: string
}>(), {
  nodes: () => [], modelValue: () => [], expandedKeys: () => [], multiple: false, disabled: false, label: '树形选择', emptyText: '暂无可选项',
})
const emit = defineEmits<{
  'update:modelValue': [value: PhoenixAdminTreeValue[]]
  'update:expandedKeys': [value: PhoenixAdminTreeValue[]]
  select: [node: PhoenixAdminTreeNode, selected: boolean]
  expand: [node: PhoenixAdminTreeNode, expanded: boolean]
}>()
const expanded = computed(() => new Set(props.expandedKeys))
const selected = computed(() => new Set(props.modelValue))
const flatNodes = computed(() => {
  const result: FlatNode[] = []
  const visit = (nodes: PhoenixAdminTreeNode[], level: number, parent: PhoenixAdminTreeValue | null) => nodes.forEach((node) => {
    result.push({ node, level, parent })
    if (node.children?.length && expanded.value.has(node.value)) visit(node.children, level + 1, node.value)
  })
  visit(props.nodes, 1, null)
  return result
})
function toggleExpand(node: PhoenixAdminTreeNode, force?: boolean) {
  if (!node.children?.length || props.disabled || node.disabled) return
  const next = new Set(props.expandedKeys)
  const open = force ?? !next.has(node.value)
  if (open) next.add(node.value)
  else next.delete(node.value)
  emit('update:expandedKeys', [...next])
  emit('expand', node, open)
}
function selectNode(node: PhoenixAdminTreeNode) {
  if (props.disabled || node.disabled) return
  const active = selected.value.has(node.value)
  const next = props.multiple
    ? active ? props.modelValue.filter((item) => item !== node.value) : [...props.modelValue, node.value]
    : active ? [] : [node.value]
  emit('update:modelValue', [...new Set(next)])
  emit('select', node, !active)
}
function keydown(event: KeyboardEvent, item: FlatNode) {
  if (event.key === 'ArrowRight') toggleExpand(item.node, true)
  else if (event.key === 'ArrowLeft') toggleExpand(item.node, false)
  else if (event.key === 'Enter' || event.key === ' ') selectNode(item.node)
  else return
  event.preventDefault()
}
</script>

<template>
  <section class="px-tree-select" :aria-label="label">
    <div v-if="flatNodes.length" role="tree" :aria-multiselectable="multiple">
      <div v-for="item in flatNodes" :key="item.node.value" role="treeitem" :aria-level="item.level" :aria-expanded="item.node.children?.length ? expanded.has(item.node.value) : undefined" :aria-selected="selected.has(item.node.value)" :aria-disabled="disabled || item.node.disabled" :style="{ '--px-tree-level': item.level }" tabindex="0" @keydown="keydown($event, item)">
        <button v-if="item.node.children?.length" type="button" tabindex="-1" :aria-label="expanded.has(item.node.value) ? `收起${item.node.label}` : `展开${item.node.label}`" :disabled="disabled || item.node.disabled" @click="toggleExpand(item.node)">{{ expanded.has(item.node.value) ? '−' : '+' }}</button>
        <span v-else aria-hidden="true"></span>
        <button type="button" tabindex="-1" :disabled="disabled || item.node.disabled" @click="selectNode(item.node)">{{ item.node.label }}</button>
      </div>
    </div>
    <p v-else class="px-admin-state" role="status">{{ emptyText }}</p>
  </section>
</template>
