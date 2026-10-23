<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { tx, localizeBackendMessage } from '@/i18n'
import { useAgentApi, emptyAgentPage, type AgentSubmission, type WorkbenchOrder } from '../lib/agentWorkbench'
import { orderSelection } from '../lib/workbenchOrders'
import AgentPagination from './AgentPagination.vue'
import AgentSubmissionForm from './AgentSubmissionForm.vue'
import AgentOrderNumberLink from './AgentOrderNumberLink.vue'
import AgentOrdersPanel from './AgentOrdersPanel.vue'

const props = defineProps<{ initialOrderIds: number[] }>()
const emit = defineEmits<{ close: []; created: [submission: AgentSubmission] }>()
const api = useAgentApi(), query = ref(''), loading = ref(false), initializing = ref(true), error = ref('')
const rows = ref(emptyAgentPage<WorkbenchOrder>()), selected = ref<Record<number, WorkbenchOrder>>({})
const selectedOrders = computed<WorkbenchOrder[]>(() => orderSelection(selected.value))
const previewOrderId = ref<number | null>(null)
let generation = 0, lifecycle = 0
async function load(page = 1) {
  const current = ++generation
  loading.value = true; error.value = ''
  try {
    const result = await api.fetchWorkbenchOrders({ page, pageSize: 20, query: query.value.trim(), statusCode: 'ready' })
    if (current === generation) rows.value = result
  } catch (e) { if (current === generation) error.value = e instanceof Error ? e.message : tx('Unable to load orders.') }
  finally { if (current === generation) loading.value = false }
}
function select(row: WorkbenchOrder, checked: boolean) {
  if (initializing.value || api.busy.value) return
  if (checked && row.canSubmit) selected.value[row.id] = row
  else delete selected.value[row.id]
}
onMounted(async () => {
  const current = ++lifecycle
  await load()
  if (current !== lifecycle) return
  const initial = await Promise.allSettled(props.initialOrderIds.map(id => api.fetchWorkbenchOrder(id)))
  if (current !== lifecycle) return
  let unavailable = false
  for (const result of initial) {
    if (result.status === 'fulfilled' && result.value.order.canSubmit) selected.value[result.value.order.id] = result.value.order
    else unavailable = true
  }
  initializing.value = false
  if (unavailable) error.value = tx('Some selected orders are no longer available for submission. Select the remaining orders below.')
})
onBeforeUnmount(() => { lifecycle++; generation++ })
</script>

<template>
  <section class="submit-create" data-testid="agent-submit-create-page">
    <div class="agent-toolbar"><h2>{{ $tx('New Submit') }}</h2><el-button :disabled="api.busy.value" @click="emit('close')">{{ $tx('Back to Submit') }}</el-button></div>
    <el-alert v-if="error" :title="localizeBackendMessage(error)" type="warning" :closable="false" />
    <section class="submit-order-picker">
      <h3>{{ $tx('Select orders') }}</h3>
      <form class="agent-list-filters" @submit.prevent="load()">
        <label class="agent-filter-field agent-filter-grow">{{ $tx('Search orders') }}<input v-model="query" :placeholder="$tx('Order ID, customer or card name')" :disabled="api.busy.value || initializing" data-testid="agent-submit-order-query" /></label>
        <el-button native-type="submit" :loading="loading" :disabled="api.busy.value || initializing">{{ $tx('Search') }}</el-button>
      </form>
      <div class="agent-table-scroll"><table class="agent-results-table" data-testid="agent-submit-orders-table">
        <thead><tr><th>{{ $tx('Select') }}</th><th>{{ $tx('Order ID') }}</th><th>{{ $tx('Customer') }}</th><th>{{ $tx('Cards') }}</th></tr></thead>
        <tbody><tr v-if="loading"><td colspan="4">{{ $tx('Loading…') }}</td></tr><tr v-else-if="!rows.items.length"><td colspan="4">{{ $tx('No orders ready to submit.') }}</td></tr>
          <tr v-for="row in loading ? [] : rows.items" :key="row.id"><td class="agent-select-column"><input type="checkbox" :checked="Boolean(selected[row.id])" :disabled="api.busy.value || initializing || !row.canSubmit" :aria-label="$tx('Select order {number}', { number: row.orderNo })" :data-testid="`agent-submit-select-${row.id}`" @change="select(row, ($event.target as HTMLInputElement).checked)" /></td><td><AgentOrderNumberLink :order-id="row.id" :order-no="row.orderNo" :company-id="api.companyId" :disabled="api.busy.value || initializing" @preview="previewOrderId = row.id" /></td><td>{{ row.clientName }}</td><td>{{ row.cardCount }}</td></tr>
        </tbody>
      </table></div>
      <AgentPagination v-bind="rows" :disabled="loading || api.busy.value" @change="load" />
      <div v-if="selectedOrders.length" class="submit-selected" data-testid="agent-submit-selected-orders"><span>{{ $tx('{count} orders selected', { count: selectedOrders.length }) }}</span><el-tag v-for="row in selectedOrders" :key="row.id" :closable="!api.busy.value" @close="select(row, false)">{{ row.orderNo }} · {{ row.clientName }}</el-tag></div>
      <p v-else class="muted-copy">{{ $tx('Select orders ready to submit.') }}</p>
    </section>
    <p v-if="initializing" role="status">{{ $tx('Preparing selected orders…') }}</p>
    <AgentSubmissionForm v-if="!initializing && selectedOrders.length" :orders="selectedOrders" @created="emit('created', $event)" @close="emit('close')" />
    <AgentOrdersPanel v-if="previewOrderId" :key="previewOrderId" :initial-record-id="previewOrderId" detail-only @closed="previewOrderId = null" />
  </section>
</template>

<style scoped>
.submit-create{display:grid;gap:20px}.submit-order-picker{padding:20px;border:1px solid var(--el-border-color-light);border-radius:8px}.submit-order-picker h3{margin-top:0}.submit-selected{display:flex;gap:10px;flex-wrap:wrap;align-items:center}.submit-order-picker .agent-results-table{min-width:560px}.submit-order-picker .agent-table-scroll{max-height:360px}@media(max-width:650px){.submit-order-picker{padding:12px}}
</style>
