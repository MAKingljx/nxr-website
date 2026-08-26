<script setup lang="ts">
import {
  PhoenixAdvancedTable,
  PhoenixBatchActionBar,
  PhoenixImportExportPanel,
  PhoenixQueryPanel,
} from '../../../primitives/admin'
import type {
  PhoenixAdvancedColumn,
  PhoenixAdvancedRow,
  PhoenixAdvancedSort,
  PhoenixBatchAction,
  PhoenixEditingCell,
  PhoenixQueryField,
  PhoenixQueryValue,
  PhoenixRowKey,
} from '../../../primitives/admin'
import PhoenixManagementPageShell from '../../management/PhoenixManagementPageShell.vue'
import type { PhoenixManagementAction, PhoenixManagementStat } from '../../management/PhoenixManagementPageShell.vue'

withDefaults(defineProps<{
  title?: string
  stats?: PhoenixManagementStat[]
  rows?: PhoenixAdvancedRow[]
  columns?: PhoenixAdvancedColumn[]
  queryFields?: PhoenixQueryField[]
  query?: Record<string, PhoenixQueryValue>
  selectedKeys?: PhoenixRowKey[]
  sort?: PhoenixAdvancedSort | null
  tableFilters?: Record<string, string>
  batchActions?: PhoenixBatchAction[]
  dataFormat?: string
  loading?: boolean
  importing?: boolean
  exporting?: boolean
  showImportExport?: boolean
}>(), {
  title: '系统数据管理',
  stats: () => [],
  rows: () => [],
  columns: () => [],
  queryFields: () => [],
  query: () => ({}),
  selectedKeys: () => [],
  sort: null,
  tableFilters: () => ({}),
  batchActions: () => [],
  dataFormat: 'xlsx',
  loading: false,
  importing: false,
  exporting: false,
  showImportExport: true,
})

const emit = defineEmits<{
  'update:query': [value: Record<string, PhoenixQueryValue>]
  'update:selectedKeys': [keys: PhoenixRowKey[]]
  'update:dataFormat': [value: string]
  query: [value: Record<string, PhoenixQueryValue>]
  reset: []
  create: []
  sortChange: [sort: PhoenixAdvancedSort]
  filterChange: [filters: Record<string, string>]
  batchAction: [action: PhoenixBatchAction]
  clearSelection: []
  importRequest: [file: File, format: string]
  exportRequest: [format: string]
  editRequest: [cell: PhoenixEditingCell, row: PhoenixAdvancedRow]
  editCommit: [cell: PhoenixEditingCell, value: string, row: PhoenixAdvancedRow]
}>()

const pageActions: PhoenixManagementAction[] = [{ id: 'create', label: '新增数据', variant: 'primary' }]

function forwardEditRequest(cell: PhoenixEditingCell, row: PhoenixAdvancedRow) {
  emit('editRequest', cell, row)
}

function forwardEditCommit(cell: PhoenixEditingCell, value: string, row: PhoenixAdvancedRow) {
  emit('editCommit', cell, value, row)
}

function forwardImport(file: File, format: string) {
  emit('importRequest', file, format)
}
</script>

<template>
  <PhoenixManagementPageShell class="px-admin-workspace" :title="title" :stats="stats" :actions="pageActions" :busy="loading" content-label="数据列表" detail-label="导入导出" @action="emit('create')">
    <template #filters>
      <PhoenixQueryPanel :fields="queryFields" :model-value="query" :loading="loading" @update:model-value="emit('update:query', $event)" @query="emit('query', $event)" @reset="emit('reset')" />
    </template>
    <div class="px-admin-workspace__content">
      <PhoenixBatchActionBar :selected-count="selectedKeys.length" :actions="batchActions" :disabled="loading" @action="emit('batchAction', $event)" @clear="emit('clearSelection')" />
      <PhoenixAdvancedTable
        :rows="rows"
        :columns="columns"
        :selected-keys="selectedKeys"
        :sort="sort"
        :filters="tableFilters"
        :loading="loading"
        @update:selected-keys="emit('update:selectedKeys', $event)"
        @sort-change="emit('sortChange', $event)"
        @filter-change="emit('filterChange', $event)"
        @edit-request="forwardEditRequest"
        @edit-commit="forwardEditCommit"
      />
    </div>
    <template v-if="showImportExport" #detail>
      <PhoenixImportExportPanel :model-value="dataFormat" :importing="importing" :exporting="exporting" :disabled="loading" @update:model-value="emit('update:dataFormat', $event)" @import-request="forwardImport" @export-request="emit('exportRequest', $event)" />
    </template>
    <template #footer><slot name="footer" /></template>
  </PhoenixManagementPageShell>
</template>
