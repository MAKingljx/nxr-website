<script setup lang="ts">
import PhoenixManagementPageShell from './PhoenixManagementPageShell.vue'
import type { PhoenixManagementAction, PhoenixManagementStat } from './PhoenixManagementPageShell.vue'

withDefaults(defineProps<{ stats?: PhoenixManagementStat[]; busy?: boolean }>(), { stats: () => [], busy: false })
const emit = defineEmits<{ refresh: []; export: [] }>()
const actions: PhoenixManagementAction[] = [{ id: 'refresh', label: '刷新日志' }, { id: 'export', label: '导出日志', variant: 'primary' }]
function action(id: string) { if (id === 'refresh') emit('refresh'); if (id === 'export') emit('export') }
</script>

<template><PhoenixManagementPageShell title="审计日志" :stats="stats" :actions="actions" :busy="busy" content-label="审计日志列表" detail-label="日志详情" @action="action"><template #filters><slot name="filters" /></template><slot /><template #detail><slot name="detail" /></template></PhoenixManagementPageShell></template>
