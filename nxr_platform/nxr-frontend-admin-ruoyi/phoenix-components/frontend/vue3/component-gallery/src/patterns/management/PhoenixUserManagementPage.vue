<script setup lang="ts">
import PhoenixManagementPageShell from './PhoenixManagementPageShell.vue'
import type { PhoenixManagementAction, PhoenixManagementStat } from './PhoenixManagementPageShell.vue'

withDefaults(defineProps<{ stats?: PhoenixManagementStat[]; busy?: boolean }>(), { stats: () => [], busy: false })
const emit = defineEmits<{ create: []; import: []; export: [] }>()
const actions: PhoenixManagementAction[] = [
  { id: 'import', label: '导入用户' },
  { id: 'export', label: '导出用户' },
  { id: 'create', label: '新增用户', variant: 'primary' },
]
function action(id: string) {
  if (id === 'create') emit('create')
  if (id === 'import') emit('import')
  if (id === 'export') emit('export')
}
</script>

<template><PhoenixManagementPageShell title="用户管理" :stats="stats" :actions="actions" :busy="busy" content-label="用户列表" detail-label="用户详情" @action="action"><template #filters><slot name="filters" /></template><slot /><template #detail><slot name="detail" /></template><template #footer><slot name="footer" /></template></PhoenixManagementPageShell></template>
