<script setup lang="ts">
import PhoenixWorkspacePageShell from './PhoenixWorkspacePageShell.vue'
import type { PhoenixWorkspaceAction, PhoenixWorkspaceNavigationItem } from './PhoenixWorkspacePageShell.vue'

withDefaults(defineProps<{ busy?: boolean; unread?: number; activeNavigation?: string }>(), { busy: false, unread: 0, activeNavigation: 'all' })
const emit = defineEmits<{ navigate: [id: string]; compose: []; markAllRead: [] }>()
const navigation = (unread: number): PhoenixWorkspaceNavigationItem[] => [
  { id: 'all', label: '全部消息' }, { id: 'unread', label: '未读消息', badge: Math.max(0, unread) }, { id: 'system', label: '系统通知' }, { id: 'mentions', label: '提到我的' },
]
const actions: PhoenixWorkspaceAction[] = [{ id: 'read', label: '全部已读' }, { id: 'compose', label: '新建消息', primary: true }]
function action(id: string) { if (id === 'read') emit('markAllRead'); if (id === 'compose') emit('compose') }
</script>

<template><PhoenixWorkspacePageShell title="消息中心" :active-navigation="activeNavigation" :navigation="navigation(unread)" :actions="actions" :busy="busy" @navigate="emit('navigate', $event)" @action="action"><template #brand><slot name="brand" /></template><template #topbar><slot name="topbar" /></template><div class="px-workspace-split"><section aria-label="消息列表"><slot name="list" /></section><section aria-label="消息内容"><slot name="detail" /></section></div><template #aside><slot name="aside" /></template></PhoenixWorkspacePageShell></template>
