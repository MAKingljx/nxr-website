<script setup lang="ts">
import PhoenixManagementPageShell from './PhoenixManagementPageShell.vue'
import type { PhoenixManagementAction, PhoenixManagementStat } from './PhoenixManagementPageShell.vue'

withDefaults(defineProps<{ stats?: PhoenixManagementStat[]; busy?: boolean }>(), { stats: () => [], busy: false })
const emit = defineEmits<{ create: []; edit: [] }>()
const actions: PhoenixManagementAction[] = [{ id: 'edit', label: '编辑部门' }, { id: 'create', label: '新增部门', variant: 'primary' }]
function action(id: string) { if (id === 'create') emit('create'); if (id === 'edit') emit('edit') }
</script>

<template><PhoenixManagementPageShell title="部门管理" :stats="stats" :actions="actions" :busy="busy" sidebar-label="组织架构" content-label="部门成员" @action="action"><template #sidebar><slot name="tree" /></template><slot name="members" /><template #detail><slot name="detail" /></template></PhoenixManagementPageShell></template>
