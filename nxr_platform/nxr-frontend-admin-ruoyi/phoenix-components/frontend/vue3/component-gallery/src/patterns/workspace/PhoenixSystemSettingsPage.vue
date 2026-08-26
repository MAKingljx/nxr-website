<script setup lang="ts">
import PhoenixWorkspacePageShell from './PhoenixWorkspacePageShell.vue'
import type { PhoenixWorkspaceAction, PhoenixWorkspaceNavigationItem } from './PhoenixWorkspacePageShell.vue'

withDefaults(defineProps<{ busy?: boolean; section?: string }>(), { busy: false, section: 'general' })
const emit = defineEmits<{ navigate: [id: string]; save: []; reset: [] }>()
const navigation: PhoenixWorkspaceNavigationItem[] = [{ id: 'general', label: '基础配置' }, { id: 'branding', label: '品牌外观' }, { id: 'notifications', label: '通知规则' }, { id: 'integrations', label: '服务集成' }, { id: 'security', label: '安全策略' }]
const actions: PhoenixWorkspaceAction[] = [{ id: 'reset', label: '恢复默认' }, { id: 'save', label: '保存配置', primary: true }]
function action(id: string) { if (id === 'reset') emit('reset'); if (id === 'save') emit('save') }
</script>

<template><PhoenixWorkspacePageShell title="系统设置" :active-navigation="section" :navigation="navigation" :actions="actions" :busy="busy" @navigate="emit('navigate', $event)" @action="action"><template #brand><slot name="brand" /></template><template #topbar><slot name="topbar" /></template><section class="px-workspace-settings" aria-label="系统设置表单"><slot /></section><template #aside><slot name="audit" /></template></PhoenixWorkspacePageShell></template>
