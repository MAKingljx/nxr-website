<template>
  <phoenix-server-data-workbench
    class="nxr-server-data-workbench"
    :class="{ 'is-flush': flush }"
    :loading="loading"
    :error="error"
    :empty="empty"
    :empty-title="emptyTitle"
    :empty-description="emptyDescription"
    :total="total"
    :page="page"
    :page-size="pageSize"
    :page-size-options="pageSizeOptions"
    :show-reset="showReset"
    :aria-label="ariaLabel"
    @query="$emit('query', $event)"
    @reset="$emit('reset')"
    @retry="$emit('retry')"
    @update:page="$emit('update:page', $event)"
    @update:page-size="$emit('update:pageSize', $event)"
    @page-change="forwardPageChange"
  >
    <template v-if="$slots.filters" #filters="scope">
      <slot name="filters" v-bind="scope" />
    </template>
    <template v-if="$slots['filter-actions']" #filter-actions="scope">
      <slot name="filter-actions" v-bind="scope" />
    </template>
    <template v-if="$slots.toolbar" #toolbar="scope">
      <slot name="toolbar" v-bind="scope" />
    </template>
    <template #default="scope">
      <slot v-bind="scope" />
    </template>
    <template v-if="$slots.loading" #loading="scope">
      <slot name="loading" v-bind="scope" />
    </template>
    <template v-if="$slots.error" #error="scope">
      <slot name="error" v-bind="scope" />
    </template>
    <template v-if="$slots.empty" #empty="scope">
      <slot name="empty" v-bind="scope" />
    </template>
    <template v-if="$slots.pagination" #pagination="scope">
      <slot name="pagination" v-bind="scope" />
    </template>
    <template v-if="$slots.footer" #footer="scope">
      <slot name="footer" v-bind="scope" />
    </template>
  </phoenix-server-data-workbench>
</template>

<script setup>
import { PhoenixServerDataWorkbench } from '@phoenix-server-data-workbench/index.ts'

defineProps({
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  empty: { type: Boolean, default: false },
  emptyTitle: { type: String, default: '' },
  emptyDescription: { type: String, default: '' },
  total: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  pageSize: { type: Number, default: 20 },
  pageSizeOptions: { type: Array, default: () => [10, 20, 50] },
  showReset: { type: Boolean, default: true },
  ariaLabel: { type: String, default: '服务端数据列表' },
  flush: { type: Boolean, default: true }
})

const emit = defineEmits([
  'query',
  'reset',
  'retry',
  'update:page',
  'update:pageSize',
  'pageChange'
])

function forwardPageChange(page, pageSize) {
  emit('pageChange', page, pageSize)
}
</script>

<style scoped>
.nxr-server-data-workbench {
  --psdw-color-surface: var(--nxr-surface);
  --psdw-color-subtle: var(--nxr-surface-subtle);
  --psdw-color-text: var(--nxr-text);
  --psdw-color-muted: var(--nxr-text-muted);
  --psdw-color-border: var(--nxr-border);
  --psdw-color-primary: var(--nxr-accent);
  --psdw-color-primary-soft: var(--nxr-accent-soft);
  --psdw-color-danger: var(--nxr-danger);
  --psdw-color-danger-soft: var(--nxr-danger-soft);
  --psdw-radius: 8px;
  --psdw-font-family: inherit;
}

.nxr-server-data-workbench.is-flush :deep(.psdw-content) {
  padding: 0;
}

.nxr-server-data-workbench.is-flush :deep(.psdw-state) {
  margin: 16px;
}

.nxr-server-data-workbench :deep(.psdw-pagination-shell) {
  background: var(--nxr-surface-subtle);
}
</style>
