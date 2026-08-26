<script setup lang="ts">
import PhoenixWorkspacePageShell from './PhoenixWorkspacePageShell.vue'
import type { PhoenixWorkspaceAction, PhoenixWorkspaceNavigationItem } from './PhoenixWorkspacePageShell.vue'

withDefaults(defineProps<{ busy?: boolean; section?: string }>(), { busy: false, section: 'profile' })
const emit = defineEmits<{ navigate: [id: string]; save: [] }>()
const navigation: PhoenixWorkspaceNavigationItem[] = [{ id: 'profile', label: '基本资料' }, { id: 'security', label: '账户安全' }, { id: 'sessions', label: '登录设备' }, { id: 'preferences', label: '使用偏好' }]
const actions: PhoenixWorkspaceAction[] = [{ id: 'save', label: '保存设置', primary: true }]
</script>

<template><PhoenixWorkspacePageShell title="个人设置" :active-navigation="section" :navigation="navigation" :actions="actions" :busy="busy" @navigate="emit('navigate', $event)" @action="emit('save')"><template #brand><slot name="brand" /></template><template #topbar><slot name="topbar" /></template><section class="px-workspace-settings" aria-label="个人设置表单"><slot /></section><template #aside><slot name="aside" /></template></PhoenixWorkspacePageShell></template>
