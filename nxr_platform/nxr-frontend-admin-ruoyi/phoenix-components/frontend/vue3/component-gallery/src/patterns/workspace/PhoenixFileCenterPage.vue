<script setup lang="ts">
import PhoenixWorkspacePageShell from './PhoenixWorkspacePageShell.vue'
import type { PhoenixWorkspaceAction, PhoenixWorkspaceNavigationItem } from './PhoenixWorkspacePageShell.vue'

withDefaults(defineProps<{ busy?: boolean; activeNavigation?: string }>(), { busy: false, activeNavigation: 'recent' })
const emit = defineEmits<{ navigate: [id: string]; upload: []; createFolder: [] }>()
const navigation: PhoenixWorkspaceNavigationItem[] = [{ id: 'recent', label: '最近文件' }, { id: 'mine', label: '我的文件' }, { id: 'shared', label: '共享给我' }, { id: 'trash', label: '回收站' }]
const actions: PhoenixWorkspaceAction[] = [{ id: 'folder', label: '新建文件夹' }, { id: 'upload', label: '选择文件', primary: true }]
function action(id: string) { if (id === 'folder') emit('createFolder'); if (id === 'upload') emit('upload') }
</script>

<template><PhoenixWorkspacePageShell title="文件中心" :active-navigation="activeNavigation" :navigation="navigation" :actions="actions" :busy="busy" @navigate="emit('navigate', $event)" @action="action"><template #brand><slot name="brand" /></template><template #topbar><slot name="topbar" /></template><div class="px-workspace-file-grid"><section aria-label="文件夹"><slot name="folders" /></section><section aria-label="文件列表"><slot name="files" /></section></div><template #aside><slot name="preview" /></template></PhoenixWorkspacePageShell></template>
