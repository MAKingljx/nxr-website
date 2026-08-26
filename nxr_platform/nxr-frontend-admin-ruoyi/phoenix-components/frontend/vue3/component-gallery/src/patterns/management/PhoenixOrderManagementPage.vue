<script setup lang="ts">
import PhoenixManagementPageShell from './PhoenixManagementPageShell.vue'
import type { PhoenixManagementAction, PhoenixManagementStat } from './PhoenixManagementPageShell.vue'

withDefaults(defineProps<{ stats?: PhoenixManagementStat[]; busy?: boolean }>(), { stats: () => [], busy: false })
const emit = defineEmits<{ batch: []; export: [] }>()
const actions: PhoenixManagementAction[] = [{ id: 'batch', label: '批量处理' }, { id: 'export', label: '导出订单', variant: 'primary' }]
function action(id: string) { if (id === 'batch') emit('batch'); if (id === 'export') emit('export') }
</script>

<template><PhoenixManagementPageShell title="订单管理" :stats="stats" :actions="actions" :busy="busy" content-label="订单列表" detail-label="订单详情" @action="action"><template #filters><slot name="filters" /></template><slot /><template #detail><slot name="detail" /></template></PhoenixManagementPageShell></template>
