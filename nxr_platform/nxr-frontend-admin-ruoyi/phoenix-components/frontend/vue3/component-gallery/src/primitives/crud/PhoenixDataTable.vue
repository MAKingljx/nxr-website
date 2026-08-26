<script setup lang="ts">
import { computed } from 'vue'

export type PhoenixTableKey = string | number
export type PhoenixTableRow = Record<string, unknown>
export type PhoenixSortDirection = 'asc' | 'desc' | null

export interface PhoenixDataTableColumn {
  key: string
  label: string
  sortable?: boolean
  align?: 'left' | 'center' | 'right'
  width?: string | number
  format?: (value: unknown, row: PhoenixTableRow, index: number) => string | number | null | undefined
}

const props = withDefaults(
  defineProps<{
    rows: PhoenixTableRow[]
    columns: PhoenixDataTableColumn[]
    rowKey?: string | ((row: PhoenixTableRow, index: number) => PhoenixTableKey)
    modelValue?: PhoenixTableKey[]
    selectable?: boolean
    loading?: boolean
    loadingRows?: number
    emptyText?: string
    loadingText?: string
    ariaLabel?: string
    sortBy?: string
    sortDirection?: PhoenixSortDirection
    stickyHeader?: boolean
  }>(),
  {
    rowKey: 'id',
    modelValue: () => [],
    selectable: false,
    loading: false,
    loadingRows: 3,
    emptyText: '暂无数据',
    loadingText: '正在加载数据',
    ariaLabel: '数据表格',
    sortBy: '',
    sortDirection: null,
    stickyHeader: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [keys: PhoenixTableKey[]]
  'selection-change': [keys: PhoenixTableKey[]]
  'update:sortBy': [key: string]
  'update:sortDirection': [direction: PhoenixSortDirection]
  'sort-change': [sort: { key: string; direction: PhoenixSortDirection }]
  'row-click': [row: PhoenixTableRow, index: number]
}>()

function getRowKey(row: PhoenixTableRow, index: number): PhoenixTableKey {
  if (typeof props.rowKey === 'function') return props.rowKey(row, index)
  const value = row[props.rowKey]
  return typeof value === 'string' || typeof value === 'number' ? value : index
}

function getCellValue(row: PhoenixTableRow, key: string): unknown {
  return key.split('.').reduce<unknown>((value, part) => {
    if (typeof value !== 'object' || value === null) return undefined
    return (value as PhoenixTableRow)[part]
  }, row)
}

function displayCell(column: PhoenixDataTableColumn, row: PhoenixTableRow, index: number) {
  const value = getCellValue(row, column.key)
  const formatted = column.format ? column.format(value, row, index) : value
  return formatted === undefined || formatted === null || formatted === '' ? '—' : String(formatted)
}

const rowKeys = computed(() => props.rows.map(getRowKey))
const allSelected = computed(() => rowKeys.value.length > 0 && rowKeys.value.every((key) => props.modelValue.includes(key)))
const someSelected = computed(() => !allSelected.value && rowKeys.value.some((key) => props.modelValue.includes(key)))

function commitSelection(keys: PhoenixTableKey[]) {
  emit('update:modelValue', keys)
  emit('selection-change', keys)
}

function toggleRow(row: PhoenixTableRow, index: number, checked: boolean) {
  const key = getRowKey(row, index)
  const next = checked ? [...props.modelValue.filter((entry) => entry !== key), key] : props.modelValue.filter((entry) => entry !== key)
  commitSelection(next)
}

function toggleAll(checked: boolean) {
  const visible = new Set(rowKeys.value)
  const next = checked
    ? [...props.modelValue.filter((key) => !visible.has(key)), ...rowKeys.value]
    : props.modelValue.filter((key) => !visible.has(key))
  commitSelection(next)
}

function sort(column: PhoenixDataTableColumn) {
  if (!column.sortable) return
  let direction: PhoenixSortDirection = 'asc'
  if (props.sortBy === column.key && props.sortDirection === 'asc') direction = 'desc'
  else if (props.sortBy === column.key && props.sortDirection === 'desc') direction = null
  const key = direction ? column.key : ''
  emit('update:sortBy', key)
  emit('update:sortDirection', direction)
  emit('sort-change', { key, direction })
}

function ariaSort(column: PhoenixDataTableColumn) {
  if (!column.sortable) return undefined
  if (props.sortBy !== column.key || !props.sortDirection) return 'none'
  return props.sortDirection === 'asc' ? 'ascending' : 'descending'
}

function columnStyle(column: PhoenixDataTableColumn) {
  if (column.width === undefined) return undefined
  return { width: typeof column.width === 'number' ? `${column.width}px` : column.width }
}
</script>

<template>
  <div class="px-data-table" :class="{ 'has-sticky-header': stickyHeader }" :aria-busy="loading">
    <table>
      <caption class="px-sr-only">{{ ariaLabel }}</caption>
      <thead>
        <tr>
          <th v-if="selectable" class="px-data-table__selection" scope="col">
            <input
              type="checkbox"
              aria-label="选择全部当前数据"
              :checked="allSelected"
              :indeterminate="someSelected"
              :disabled="loading || rows.length === 0"
              @change="toggleAll(($event.target as HTMLInputElement).checked)"
            />
          </th>
          <th
            v-for="column in columns"
            :key="column.key"
            scope="col"
            :class="`is-${column.align ?? 'left'}`"
            :style="columnStyle(column)"
            :aria-sort="ariaSort(column)"
          >
            <button v-if="column.sortable" type="button" class="px-data-table__sort" @click="sort(column)">
              <slot :name="`header-${column.key}`" :column="column">{{ column.label }}</slot>
              <span class="px-data-table__sort-icon" aria-hidden="true">
                {{ sortBy === column.key && sortDirection === 'asc' ? '↑' : sortBy === column.key && sortDirection === 'desc' ? '↓' : '↕' }}
              </span>
            </button>
            <slot v-else :name="`header-${column.key}`" :column="column">
              {{ column.label }}
            </slot>
          </th>
        </tr>
      </thead>
      <tbody v-if="loading">
        <tr v-for="row in Math.max(1, loadingRows)" :key="row" class="px-data-table__loading-row">
          <td v-if="selectable"><span class="px-data-table__loading-block is-check"></span></td>
          <td v-for="column in columns" :key="column.key"><span class="px-data-table__loading-block"></span></td>
        </tr>
        <tr class="px-sr-only"><td :colspan="columns.length + (selectable ? 1 : 0)">{{ loadingText }}</td></tr>
      </tbody>
      <tbody v-else-if="rows.length === 0">
        <tr><td class="px-data-table__empty" :colspan="columns.length + (selectable ? 1 : 0)">{{ emptyText }}</td></tr>
      </tbody>
      <tbody v-else>
        <tr v-for="(row, rowIndex) in rows" :key="getRowKey(row, rowIndex)" @click="emit('row-click', row, rowIndex)">
          <td v-if="selectable" class="px-data-table__selection" @click.stop>
            <input
              type="checkbox"
              :aria-label="`选择第 ${rowIndex + 1} 行`"
              :checked="modelValue.includes(getRowKey(row, rowIndex))"
              @change="toggleRow(row, rowIndex, ($event.target as HTMLInputElement).checked)"
            />
          </td>
          <td v-for="column in columns" :key="column.key" :class="`is-${column.align ?? 'left'}`">
            <slot
              :name="`cell-${column.key}`"
              :value="getCellValue(row, column.key)"
              :row="row"
              :index="rowIndex"
              :column="column"
            >
              {{ displayCell(column, row, rowIndex) }}
            </slot>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
