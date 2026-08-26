<template>
  <section
    class="psdw-workbench"
    :aria-label="ariaLabel || resolvedLabels.workbench"
    :aria-busy="loading"
    data-testid="server-data-workbench"
  >
    <form
      class="psdw-filters"
      role="search"
      :aria-label="resolvedLabels.filters"
      data-testid="filter-form"
      @submit.prevent="submitQuery"
    >
      <div class="psdw-filter-fields">
        <slot name="filters" :query="submitQuery" :reset="requestReset" />
      </div>
      <div class="psdw-filter-actions">
        <slot name="filter-actions" :query="submitQuery" :reset="requestReset">
          <button class="psdw-button psdw-button-primary" type="submit" :disabled="loading">
            {{ resolvedLabels.query }}
          </button>
          <button
            v-if="showReset"
            class="psdw-button psdw-button-secondary"
            type="button"
            :disabled="loading"
            @click="requestReset"
          >
            {{ resolvedLabels.reset }}
          </button>
        </slot>
      </div>
    </form>

    <div v-if="$slots.toolbar" class="psdw-toolbar">
      <slot
        name="toolbar"
        :page="normalizedPage"
        :page-size="normalizedPageSize"
        :total="normalizedTotal"
        :query="submitQuery"
      />
    </div>

    <div class="psdw-content">
      <slot
        v-if="loading"
        name="loading"
        :title="resolvedLabels.loadingTitle"
        :description="resolvedLabels.loadingDescription"
      >
        <div class="psdw-state" role="status" aria-live="polite" data-testid="loading-state">
          <span class="psdw-spinner" aria-hidden="true" />
          <div>
            <h2>{{ resolvedLabels.loadingTitle }}</h2>
            <p>{{ resolvedLabels.loadingDescription }}</p>
          </div>
        </div>
      </slot>

      <slot v-else-if="normalizedError" name="error" :error="normalizedError" :retry="requestRetry">
        <div class="psdw-state psdw-state-error" role="alert" data-testid="error-state">
          <div>
            <h2>{{ resolvedLabels.errorTitle }}</h2>
            <p>{{ normalizedError }}</p>
          </div>
          <button class="psdw-button psdw-button-secondary" type="button" @click="requestRetry">
            {{ resolvedLabels.retry }}
          </button>
        </div>
      </slot>

      <slot
        v-else-if="empty"
        name="empty"
        :title="resolvedEmptyTitle"
        :description="resolvedEmptyDescription"
        :reset="requestReset"
      >
        <div class="psdw-state" role="status" data-testid="empty-state">
          <div>
            <h2>{{ resolvedEmptyTitle }}</h2>
            <p>{{ resolvedEmptyDescription }}</p>
          </div>
          <button
            v-if="showReset"
            class="psdw-button psdw-button-secondary"
            type="button"
            @click="requestReset"
          >
            {{ resolvedLabels.reset }}
          </button>
        </div>
      </slot>

      <slot
        v-else
        :page="normalizedPage"
        :page-size="normalizedPageSize"
        :total="normalizedTotal"
        :total-pages="totalPages"
      />
    </div>

    <div v-if="!loading && !normalizedError && !empty" class="psdw-pagination-shell">
      <slot
        name="pagination"
        :page="normalizedPage"
        :page-size="normalizedPageSize"
        :total="normalizedTotal"
        :total-pages="totalPages"
        :page-size-options="normalizedPageSizeOptions"
        :go-to-page="goToPage"
        :set-page-size="setPageSize"
      >
        <div class="psdw-pagination-summary" aria-live="polite" aria-atomic="true">
          {{ paginationSummary }}
        </div>
        <div class="psdw-page-size">
          <label :for="pageSizeId">{{ resolvedLabels.pageSize }}</label>
          <select :id="pageSizeId" :value="normalizedPageSize" @change="onPageSizeChange">
            <option v-for="size in normalizedPageSizeOptions" :key="size" :value="size">
              {{ size }}
            </option>
          </select>
        </div>
        <nav class="psdw-pagination" :aria-label="resolvedLabels.pagination">
          <button
            class="psdw-page-button"
            type="button"
            :disabled="normalizedPage <= 1"
            :aria-label="resolvedLabels.previousPage"
            data-testid="previous-page"
            @click="goToPage(normalizedPage - 1)"
          >
            <span aria-hidden="true">←</span>
          </button>
          <span class="psdw-current-page">
            {{ resolvedLabels.page }} {{ normalizedPage }} {{ resolvedLabels.of }} {{ totalPages }}
          </span>
          <button
            class="psdw-page-button"
            type="button"
            :disabled="normalizedPage >= totalPages"
            :aria-label="resolvedLabels.nextPage"
            data-testid="next-page"
            @click="goToPage(normalizedPage + 1)"
          >
            <span aria-hidden="true">→</span>
          </button>
        </nav>
      </slot>
    </div>

    <footer v-if="$slots.footer" class="psdw-footer">
      <slot
        name="footer"
        :page="normalizedPage"
        :page-size="normalizedPageSize"
        :total="normalizedTotal"
        :total-pages="totalPages"
      />
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed, useId } from 'vue'
import type { ServerDataQueryContext, ServerDataWorkbenchLabels } from './types'

defineOptions({ name: 'PhoenixServerDataWorkbench' })

const defaultLabels: ServerDataWorkbenchLabels = {
  workbench: '服务端数据工作台',
  filters: '查询条件',
  query: '查询',
  reset: '重置',
  loadingTitle: '正在加载',
  loadingDescription: '请稍候。',
  errorTitle: '加载失败',
  retry: '重试',
  emptyTitle: '暂无数据',
  emptyDescription: '当前条件下没有可显示的数据。',
  pagination: '分页',
  previousPage: '上一页',
  nextPage: '下一页',
  pageSize: '每页数量',
  page: '第',
  of: '/',
  total: '共',
  items: '条',
}

const props = withDefaults(
  defineProps<{
    loading?: boolean
    error?: string
    empty?: boolean
    emptyTitle?: string
    emptyDescription?: string
    total?: number
    page?: number
    pageSize?: number
    pageSizeOptions?: number[]
    showReset?: boolean
    labels?: Partial<ServerDataWorkbenchLabels>
    ariaLabel?: string
  }>(),
  {
    loading: false,
    error: '',
    empty: false,
    emptyTitle: '',
    emptyDescription: '',
    total: 0,
    page: 1,
    pageSize: 10,
    pageSizeOptions: () => [10, 20, 50],
    showReset: true,
    labels: () => ({}),
    ariaLabel: '',
  },
)

const emit = defineEmits<{
  query: [context: ServerDataQueryContext]
  reset: []
  retry: []
  'update:page': [page: number]
  'update:pageSize': [pageSize: number]
  pageChange: [page: number, pageSize: number]
}>()

const pageSizeId = `${useId()}-page-size`

const resolvedLabels = computed<ServerDataWorkbenchLabels>(() => ({
  ...defaultLabels,
  ...props.labels,
}))

const validPageSizeOptions = computed(() => {
  const values: number[] = []
  for (const option of props.pageSizeOptions) {
    const normalized = positiveInteger(option, 0)
    if (normalized > 0 && !values.includes(normalized)) values.push(normalized)
  }
  return values
})

const normalizedPageSize = computed(() =>
  positiveInteger(props.pageSize, validPageSizeOptions.value[0] ?? 10),
)
const normalizedTotal = computed(() => nonNegativeInteger(props.total))
const totalPages = computed(() => Math.max(1, Math.ceil(normalizedTotal.value / normalizedPageSize.value)))
const normalizedPage = computed(() =>
  Math.min(positiveInteger(props.page, 1), totalPages.value),
)
const normalizedPageSizeOptions = computed(() => [
  normalizedPageSize.value,
  ...validPageSizeOptions.value.filter((option) => option !== normalizedPageSize.value),
])
const normalizedError = computed(() => props.error.trim())
const resolvedEmptyTitle = computed(() => props.emptyTitle.trim() || resolvedLabels.value.emptyTitle)
const resolvedEmptyDescription = computed(
  () => props.emptyDescription.trim() || resolvedLabels.value.emptyDescription,
)
const paginationSummary = computed(
  () =>
    `${resolvedLabels.value.page} ${normalizedPage.value} ${resolvedLabels.value.of} ${totalPages.value} · ` +
    `${resolvedLabels.value.total} ${normalizedTotal.value} ${resolvedLabels.value.items}`,
)

function finiteNumber(value: number): number | null {
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : null
}

function positiveInteger(value: number, fallback: number): number {
  const numeric = finiteNumber(value)
  if (numeric === null || numeric <= 0) return fallback
  return Math.max(1, Math.trunc(numeric))
}

function nonNegativeInteger(value: number): number {
  const numeric = finiteNumber(value)
  if (numeric === null || numeric <= 0) return 0
  return Math.trunc(numeric)
}

function submitQuery(): void {
  emit('query', { page: normalizedPage.value, pageSize: normalizedPageSize.value })
}

function requestReset(): void {
  emit('reset')
}

function requestRetry(): void {
  emit('retry')
}

function goToPage(page: number): void {
  const target = Math.min(positiveInteger(page, 1), totalPages.value)
  if (target === normalizedPage.value) return
  emit('update:page', target)
  emit('pageChange', target, normalizedPageSize.value)
}

function setPageSize(pageSize: number): void {
  const target = positiveInteger(pageSize, normalizedPageSize.value)
  if (target === normalizedPageSize.value) return
  emit('update:pageSize', target)
  emit('update:page', 1)
  emit('pageChange', 1, target)
}

function onPageSizeChange(event: Event): void {
  setPageSize(Number((event.target as HTMLSelectElement).value))
}
</script>
