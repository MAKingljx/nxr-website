<script setup lang="ts">
import PhoenixManagementPageShell from './PhoenixManagementPageShell.vue'
import type { PhoenixManagementAction } from './PhoenixManagementPageShell.vue'

withDefaults(defineProps<{ busy?: boolean }>(), { busy: false })
const emit = defineEmits<{ create: []; import: [] }>()
const actions: PhoenixManagementAction[] = [{ id: 'import', label: '导入字典' }, { id: 'create', label: '新增字典', variant: 'primary' }]
function action(id: string) { if (id === 'create') emit('create'); if (id === 'import') emit('import') }
</script>

<template><PhoenixManagementPageShell title="字典配置" :actions="actions" :busy="busy" sidebar-label="字典分类" content-label="字典条目" @action="action"><template #filters><slot name="filters" /></template><template #sidebar><slot name="categories" /></template><slot name="entries" /><template #detail><slot name="detail" /></template></PhoenixManagementPageShell></template>
