<!-- benchmark-capabilities: title,metrics,query,batch,table,create,import,export -->
<!-- benchmark-props: rows,stats,queryModel,selectedKeys,busy -->
<!-- benchmark-events: update:queryModel,update:selectedKeys,query,reset,batch,clearSelection,create,import,export -->
<script setup lang="ts">
import {
  PhoenixAdvancedTable,
  PhoenixBatchActionBar,
  PhoenixQueryPanel,
  PhoenixUserManagementPage,
  type PhoenixAdvancedColumn,
  type PhoenixAdvancedRow,
  type PhoenixManagementStat,
  type PhoenixQueryField,
  type PhoenixRowKey,
} from '@phoenix-components/vue3-component-gallery'

interface UserRow extends PhoenixAdvancedRow {
  id: string | number
  name: string
  department: string
  role: string
  status: string
}

type QueryModel = Record<string, string>

const props = withDefaults(defineProps<{
  rows?: UserRow[]
  stats?: PhoenixManagementStat[]
  queryModel?: QueryModel
  selectedKeys?: PhoenixRowKey[]
  busy?: boolean
}>(), {
  rows: () => [],
  stats: () => [
    { label: '用户总数', value: 128, tone: 'primary' },
    { label: '正常用户', value: 116, tone: 'success' },
    { label: '待处理', value: 12, tone: 'neutral' },
  ],
  queryModel: () => ({ name: '', status: '' }),
  selectedKeys: () => [],
  busy: false,
})

const emit = defineEmits<{
  'update:queryModel': [value: QueryModel]
  'update:selectedKeys': [value: PhoenixRowKey[]]
  query: [value: QueryModel]
  reset: []
  batch: [action: string]
  clearSelection: []
  create: []
  import: []
  export: []
}>()

const queryFields: PhoenixQueryField[] = [
  { key: 'name', label: '用户名称', type: 'search' },
  { key: 'status', label: '用户状态', type: 'select', options: [{ label: '正常', value: 'enabled' }, { label: '停用', value: 'disabled' }] },
]
const columns: PhoenixAdvancedColumn[] = [
  { key: 'name', label: '用户名称' },
  { key: 'department', label: '部门' },
  { key: 'role', label: '角色' },
  { key: 'status', label: '状态' },
]

function normalizeQuery(value: Record<string, string | number | boolean | null>): QueryModel {
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, item == null ? '' : String(item)]))
}
</script>

<template>
  <PhoenixUserManagementPage :stats="stats" :busy="busy" @create="emit('create')" @import="emit('import')" @export="emit('export')">
    <template #filters>
      <PhoenixQueryPanel
        :fields="queryFields"
        :model-value="queryModel"
        :loading="busy"
        @update:model-value="emit('update:queryModel', normalizeQuery($event))"
        @query="emit('query', normalizeQuery($event))"
        @reset="emit('reset')"
      />
    </template>

    <PhoenixBatchActionBar
      :selected-count="selectedKeys.length"
      :actions="[{ key: 'disable', label: '批量停用' }]"
      :disabled="busy"
      @action="emit('batch', $event.key)"
      @clear="emit('clearSelection')"
    />
    <PhoenixAdvancedTable
      title="用户列表"
      :rows="props.rows"
      :columns="columns"
      :selected-keys="selectedKeys"
      :loading="busy"
      @update:selected-keys="emit('update:selectedKeys', $event)"
    />
  </PhoenixUserManagementPage>
</template>
