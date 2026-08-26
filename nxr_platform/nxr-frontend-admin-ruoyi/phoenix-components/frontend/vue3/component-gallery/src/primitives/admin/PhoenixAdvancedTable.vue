<script setup lang="ts">
import { computed } from 'vue'

export type PhoenixRowKey = string | number
export type PhoenixAdvancedRow = Record<string, unknown>
export interface PhoenixAdvancedColumn {
  key: string
  label: string
  sortable?: boolean
  filterable?: boolean
  editable?: boolean
  fixed?: 'left' | 'right'
  width?: number
  align?: 'left' | 'center' | 'right'
}
export interface PhoenixAdvancedSort { key: string; direction: 'asc' | 'desc' | null }
export interface PhoenixEditingCell { rowKey: PhoenixRowKey; columnKey: string }

const props = withDefaults(defineProps<{
  rows?: PhoenixAdvancedRow[]
  columns?: PhoenixAdvancedColumn[]
  rowKey?: string
  selectedKeys?: PhoenixRowKey[]
  sort?: PhoenixAdvancedSort | null
  filters?: Record<string, string>
  editingCell?: PhoenixEditingCell | null
  editValue?: string
  loading?: boolean
  selectable?: boolean
  title?: string
  emptyText?: string
}>(), {
  rows: () => [], columns: () => [], rowKey: 'id', selectedKeys: () => [], sort: null, filters: () => ({}),
  editingCell: null, editValue: '', loading: false, selectable: true, title: '数据列表', emptyText: '暂无数据',
})

const emit = defineEmits<{
  'update:selectedKeys': [keys: PhoenixRowKey[]]
  'update:editValue': [value: string]
  selectionChange: [keys: PhoenixRowKey[]]
  sortChange: [sort: PhoenixAdvancedSort]
  filterChange: [filters: Record<string, string>]
  editRequest: [cell: PhoenixEditingCell, row: PhoenixAdvancedRow]
  editCommit: [cell: PhoenixEditingCell, value: string, row: PhoenixAdvancedRow]
  editCancel: [cell: PhoenixEditingCell]
}>()

const normalizedColumns = computed(() => props.columns.filter((column, index, list) => column.key && list.findIndex((item) => item.key === column.key) === index))
const keys = computed(() => props.rows.map(rowId).filter((value): value is PhoenixRowKey => value !== null))
const selected = computed(() => new Set(props.selectedKeys))
const allSelected = computed(() => keys.value.length > 0 && keys.value.every((key) => selected.value.has(key)))

function rowId(row: PhoenixAdvancedRow): PhoenixRowKey | null {
  const value = row[props.rowKey]
  return typeof value === 'string' || (typeof value === 'number' && Number.isFinite(value)) ? value : null
}
function columnWidth(column: PhoenixAdvancedColumn) {
  return Number.isFinite(column.width) ? Math.min(640, Math.max(72, Math.round(column.width!))) : 160
}
function columnStyle(column: PhoenixAdvancedColumn, index: number) {
  const width = columnWidth(column)
  const style: Record<string, string> = { width: `${width}px`, minWidth: `${width}px` }
  if (column.fixed === 'left') {
    const offset = normalizedColumns.value.slice(0, index).filter((item) => item.fixed === 'left').reduce((sum, item) => sum + columnWidth(item), 0)
    style.left = `${offset}px`
  }
  if (column.fixed === 'right') {
    const offset = normalizedColumns.value.slice(index + 1).filter((item) => item.fixed === 'right').reduce((sum, item) => sum + columnWidth(item), 0)
    style.right = `${offset}px`
  }
  return style
}
function setSelection(next: PhoenixRowKey[]) {
  const unique = [...new Set(next)]
  emit('update:selectedKeys', unique)
  emit('selectionChange', unique)
}
function toggleAll() {
  setSelection(allSelected.value ? props.selectedKeys.filter((key) => !keys.value.includes(key)) : [...new Set([...props.selectedKeys, ...keys.value])])
}
function toggleRow(key: PhoenixRowKey, checked: boolean) {
  setSelection(checked ? [...props.selectedKeys, key] : props.selectedKeys.filter((item) => item !== key))
}
function changeSort(column: PhoenixAdvancedColumn) {
  if (!column.sortable) return
  const direction = props.sort?.key !== column.key || props.sort.direction === null ? 'asc' : props.sort.direction === 'asc' ? 'desc' : null
  emit('sortChange', { key: column.key, direction })
}
function changeFilter(key: string, value: string) {
  emit('filterChange', { ...props.filters, [key]: value })
}
function isEditing(row: PhoenixAdvancedRow, column: PhoenixAdvancedColumn) {
  const key = rowId(row)
  return key !== null && props.editingCell?.rowKey === key && props.editingCell.columnKey === column.key
}
function requestEdit(row: PhoenixAdvancedRow, column: PhoenixAdvancedColumn) {
  const key = rowId(row)
  if (key !== null && column.editable) emit('editRequest', { rowKey: key, columnKey: column.key }, row)
}
function commit(row: PhoenixAdvancedRow, column: PhoenixAdvancedColumn) {
  const key = rowId(row)
  if (key !== null) emit('editCommit', { rowKey: key, columnKey: column.key }, props.editValue, row)
}
</script>

<template>
  <section class="px-advanced-table" :aria-label="title" :aria-busy="loading">
    <div class="px-advanced-table__scroller" tabindex="0">
      <table>
        <caption class="px-admin-sr-only">{{ title }}</caption>
        <thead>
          <tr>
            <th v-if="selectable" class="px-advanced-table__select" scope="col">
              <input type="checkbox" aria-label="选择当前页全部数据" :checked="allSelected" :disabled="!keys.length || loading" @change="toggleAll">
            </th>
            <th v-for="(column, columnIndex) in normalizedColumns" :key="column.key" scope="col" :class="[`is-${column.align || 'left'}`, column.fixed ? `is-fixed-${column.fixed}` : '']" :style="columnStyle(column, columnIndex)">
              <button v-if="column.sortable" type="button" :aria-label="`按${column.label}排序`" :aria-pressed="sort?.key === column.key && sort.direction !== null" @click="changeSort(column)">
                {{ column.label }} <span aria-hidden="true">{{ sort?.key === column.key && sort.direction === 'asc' ? '↑' : sort?.key === column.key && sort.direction === 'desc' ? '↓' : '↕' }}</span>
              </button>
              <span v-else>{{ column.label }}</span>
            </th>
          </tr>
          <tr v-if="normalizedColumns.some((column) => column.filterable)" class="px-advanced-table__filters">
            <th v-if="selectable"></th>
            <th v-for="(column, columnIndex) in normalizedColumns" :key="column.key" :class="column.fixed ? `is-fixed-${column.fixed}` : ''" :style="columnStyle(column, columnIndex)">
              <input v-if="column.filterable" type="search" :value="filters[column.key] || ''" :aria-label="`筛选${column.label}`" placeholder="筛选" @input="changeFilter(column.key, ($event.target as HTMLInputElement).value)">
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, rowIndex) in rows" :key="rowId(row) ?? rowIndex">
            <td v-if="selectable" class="px-advanced-table__select">
              <input v-if="rowId(row) !== null" type="checkbox" :checked="selected.has(rowId(row)!)" :aria-label="`选择第 ${rowIndex + 1} 行`" @change="toggleRow(rowId(row)!, ($event.target as HTMLInputElement).checked)">
            </td>
            <td v-for="(column, columnIndex) in normalizedColumns" :key="column.key" :class="[`is-${column.align || 'left'}`, column.fixed ? `is-fixed-${column.fixed}` : '']" :style="columnStyle(column, columnIndex)">
              <div v-if="isEditing(row, column)" class="px-advanced-table__editor">
                <input :value="editValue" :aria-label="`编辑${column.label}`" @input="emit('update:editValue', ($event.target as HTMLInputElement).value)" @keydown.enter.prevent="commit(row, column)" @keydown.escape.prevent="emit('editCancel', editingCell!)">
                <button type="button" @click="commit(row, column)">保存</button>
                <button type="button" @click="emit('editCancel', editingCell!)">取消</button>
              </div>
              <button v-else-if="column.editable" type="button" class="px-advanced-table__editable" :aria-label="`编辑${column.label}`" @click="requestEdit(row, column)">{{ row[column.key] ?? '—' }}</button>
              <span v-else>{{ row[column.key] ?? '—' }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="loading" class="px-admin-state" role="status">数据加载中</p>
    <p v-else-if="!rows.length" class="px-admin-state" role="status">{{ emptyText }}</p>
  </section>
</template>
