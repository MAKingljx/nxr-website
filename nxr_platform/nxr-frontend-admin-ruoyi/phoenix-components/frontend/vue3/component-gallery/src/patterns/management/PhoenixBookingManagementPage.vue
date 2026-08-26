<script setup lang="ts">
import PhoenixManagementPageShell from './PhoenixManagementPageShell.vue'
import type { PhoenixManagementAction, PhoenixManagementStat } from './PhoenixManagementPageShell.vue'

withDefaults(defineProps<{ stats?: PhoenixManagementStat[]; busy?: boolean }>(), { stats: () => [], busy: false })
const emit = defineEmits<{ create: []; export: [] }>()
const actions: PhoenixManagementAction[] = [{ id: 'export', label: '导出预约' }, { id: 'create', label: '新增预约', variant: 'primary' }]
function action(id: string) { if (id === 'create') emit('create'); if (id === 'export') emit('export') }
</script>

<template><PhoenixManagementPageShell title="预约管理" :stats="stats" :actions="actions" :busy="busy" content-label="预约列表" detail-label="预约日历" @action="action"><template #filters><slot name="filters" /></template><slot /><template #detail><slot name="calendar" /></template></PhoenixManagementPageShell></template>
