<script setup lang="ts">
import PhoenixManagementPageShell from './PhoenixManagementPageShell.vue'
import type { PhoenixManagementAction, PhoenixManagementStat } from './PhoenixManagementPageShell.vue'

withDefaults(defineProps<{ stats?: PhoenixManagementStat[]; busy?: boolean }>(), { stats: () => [], busy: false })
const emit = defineEmits<{ create: []; import: []; export: [] }>()
const actions: PhoenixManagementAction[] = [{ id: 'import', label: '导入商品' }, { id: 'export', label: '导出商品' }, { id: 'create', label: '新增商品', variant: 'primary' }]
function action(id: string) { if (id === 'create') emit('create'); if (id === 'import') emit('import'); if (id === 'export') emit('export') }
</script>

<template><PhoenixManagementPageShell title="商品管理" :stats="stats" :actions="actions" :busy="busy" content-label="商品列表" detail-label="商品编辑" @action="action"><template #filters><slot name="filters" /></template><slot /><template #detail><slot name="editor" /></template></PhoenixManagementPageShell></template>
