<script setup lang="ts">
import PhoenixManagementPageShell from './PhoenixManagementPageShell.vue'
import type { PhoenixManagementAction, PhoenixManagementStat } from './PhoenixManagementPageShell.vue'

withDefaults(defineProps<{ stats?: PhoenixManagementStat[]; busy?: boolean }>(), { stats: () => [], busy: false })
const emit = defineEmits<{ create: []; save: [] }>()
const actions: PhoenixManagementAction[] = [{ id: 'create', label: '新增角色' }, { id: 'save', label: '保存权限', variant: 'primary' }]
function action(id: string) { if (id === 'create') emit('create'); if (id === 'save') emit('save') }
</script>

<template><PhoenixManagementPageShell title="角色权限管理" :stats="stats" :actions="actions" :busy="busy" sidebar-label="角色列表" content-label="权限矩阵" @action="action"><template #sidebar><slot name="roles" /></template><slot name="permissions" /><template #detail><slot name="detail" /></template></PhoenixManagementPageShell></template>
