<script setup lang="ts">
import PhoenixManagementPageShell from './PhoenixManagementPageShell.vue'
import type { PhoenixManagementAction, PhoenixManagementStat } from './PhoenixManagementPageShell.vue'

withDefaults(defineProps<{ stats?: PhoenixManagementStat[]; busy?: boolean }>(), { stats: () => [], busy: false })
const emit = defineEmits<{ stocktake: []; export: [] }>()
const actions: PhoenixManagementAction[] = [{ id: 'stocktake', label: '发起盘点' }, { id: 'export', label: '导出库存', variant: 'primary' }]
function action(id: string) { if (id === 'stocktake') emit('stocktake'); if (id === 'export') emit('export') }
</script>

<template><PhoenixManagementPageShell title="库存管理" :stats="stats" :actions="actions" :busy="busy" content-label="库存列表" detail-label="库存预警" @action="action"><template #filters><slot name="filters" /></template><slot /><template #detail><slot name="alerts" /></template></PhoenixManagementPageShell></template>
