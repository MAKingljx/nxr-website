<script setup lang="ts">
import PhoenixWorkspacePageShell from './PhoenixWorkspacePageShell.vue'
import type { PhoenixWorkspaceAction, PhoenixWorkspaceNavigationItem } from './PhoenixWorkspacePageShell.vue'

withDefaults(defineProps<{ busy?: boolean; activeNavigation?: string }>(), { busy: false, activeNavigation: 'home' })
const emit = defineEmits<{ navigate: [id: string]; create: [] }>()
const navigation: PhoenixWorkspaceNavigationItem[] = [
  { id: 'home', label: '工作台' },
  { id: 'messages', label: '消息中心', badge: 8 },
  { id: 'files', label: '文件中心' },
  { id: 'settings', label: '设置' },
]
const actions: PhoenixWorkspaceAction[] = [{ id: 'create', label: '新建内容', primary: true }]
</script>

<template>
  <PhoenixWorkspacePageShell title="工作台" :navigation="navigation" :active-navigation="activeNavigation" :actions="actions" :busy="busy" @navigate="emit('navigate', $event)" @action="emit('create')">
    <template #brand><slot name="brand" /></template>
    <template #topbar><slot name="topbar" /></template>
    <div class="px-workspace-home__metrics"><slot name="metrics" /></div>
    <div class="px-workspace-home__grid"><section aria-label="待办事项"><slot name="tasks" /></section><section aria-label="最近动态"><slot name="activity" /></section></div>
    <template #aside><slot name="aside" /></template>
    <template #footer><slot name="footer" /></template>
  </PhoenixWorkspacePageShell>
</template>
