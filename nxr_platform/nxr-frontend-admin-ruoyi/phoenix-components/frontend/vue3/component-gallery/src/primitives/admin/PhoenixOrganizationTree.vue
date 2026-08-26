<script setup lang="ts">
import { computed } from 'vue'

export type PhoenixOrganizationValue = string | number
export interface PhoenixOrganizationNode {
  id: PhoenixOrganizationValue
  name: string
  type?: 'company' | 'department' | 'team'
  memberCount?: number
  manager?: string
  children?: PhoenixOrganizationNode[]
  disabled?: boolean
}
interface OrganizationItem { node: PhoenixOrganizationNode; level: number }
const props = withDefaults(defineProps<{
  nodes?: PhoenixOrganizationNode[]
  selectedId?: PhoenixOrganizationValue | null
  expandedIds?: PhoenixOrganizationValue[]
  label?: string
  readonly?: boolean
}>(), { nodes: () => [], selectedId: null, expandedIds: () => [], label: '组织架构', readonly: false })
const emit = defineEmits<{
  'update:selectedId': [id: PhoenixOrganizationValue]
  'update:expandedIds': [ids: PhoenixOrganizationValue[]]
  select: [node: PhoenixOrganizationNode]
  add: [parent: PhoenixOrganizationNode]
  edit: [node: PhoenixOrganizationNode]
}>()
const expanded = computed(() => new Set(props.expandedIds))
const items = computed(() => {
  const result: OrganizationItem[] = []
  const visit = (nodes: PhoenixOrganizationNode[], level: number) => nodes.forEach((node) => {
    result.push({ node, level })
    if (node.children?.length && expanded.value.has(node.id)) visit(node.children, level + 1)
  })
  visit(props.nodes, 1)
  return result
})
function count(value?: number) { return Number.isFinite(value) ? Math.max(0, Math.floor(value!)) : 0 }
function toggle(node: PhoenixOrganizationNode) {
  if (!node.children?.length || node.disabled) return
  const next = new Set(props.expandedIds)
  if (next.has(node.id)) next.delete(node.id)
  else next.add(node.id)
  emit('update:expandedIds', [...next])
}
function select(node: PhoenixOrganizationNode) {
  if (node.disabled) return
  emit('update:selectedId', node.id)
  emit('select', node)
}
</script>

<template>
  <section class="px-organization-tree" :aria-label="label">
    <header><h3>{{ label }}</h3><slot name="actions" /></header>
    <div v-if="items.length" role="tree">
      <div v-for="item in items" :key="item.node.id" role="treeitem" :aria-level="item.level" :aria-selected="selectedId === item.node.id" :aria-expanded="item.node.children?.length ? expanded.has(item.node.id) : undefined" :aria-disabled="item.node.disabled" :style="{ '--px-tree-level': item.level }" :class="{ 'is-selected': selectedId === item.node.id }">
        <button v-if="item.node.children?.length" type="button" class="px-organization-tree__toggle" :aria-label="expanded.has(item.node.id) ? `收起${item.node.name}` : `展开${item.node.name}`" :disabled="item.node.disabled" @click="toggle(item.node)">{{ expanded.has(item.node.id) ? '−' : '+' }}</button>
        <span v-else></span>
        <button type="button" class="px-organization-tree__node" :disabled="item.node.disabled" @click="select(item.node)">
          <strong>{{ item.node.name }}</strong><small>{{ item.node.manager || '未设置负责人' }} · {{ count(item.node.memberCount) }} 人</small>
        </button>
        <div v-if="!readonly" class="px-organization-tree__actions">
          <button type="button" :disabled="item.node.disabled" :aria-label="`在${item.node.name}下新增`" @click="emit('add', item.node)">新增</button>
          <button type="button" :disabled="item.node.disabled" :aria-label="`编辑${item.node.name}`" @click="emit('edit', item.node)">编辑</button>
        </div>
      </div>
    </div>
    <p v-else class="px-admin-state" role="status">暂无组织数据</p>
  </section>
</template>
