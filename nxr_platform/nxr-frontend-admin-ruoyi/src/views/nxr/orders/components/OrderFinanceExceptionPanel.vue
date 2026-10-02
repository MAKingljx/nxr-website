<template>
  <section class="finance-exceptions" :aria-label="$tx('Payment exceptions')">
    <div class="finance-exceptions-heading"><strong>{{ $tx('Payment exceptions') }}</strong><el-button link type="primary" :loading="loading" @click="load">{{ $tx('Refresh') }}</el-button></div>
    <el-alert v-if="error" type="error" :closable="false" :title="error" />
    <el-table v-else-if="rows.length || loading" v-loading="loading" :data="rows" size="small" border>
      <el-table-column type="expand"><template #default="{ row }"><el-descriptions :column="2" border class="finance-exception-details"><el-descriptions-item :label="$tx('Transaction ID')">{{ row.providerTransactionId || '-' }}</el-descriptions-item><el-descriptions-item :label="$tx('Provider event')">{{ row.providerEventId || '-' }}</el-descriptions-item><el-descriptions-item :label="$tx('Processing Note')" :span="2">{{ row.resolutionNote || '-' }}</el-descriptions-item><el-descriptions-item :label="$tx('Resolved at')">{{ formatCustomerDate(row.resolvedAt) }}</el-descriptions-item></el-descriptions></template></el-table-column>
      <el-table-column :label="$tx('Exception')" min-width="160"><template #default="{ row }">{{ exceptionLabel(row.exceptionTypeCode) }}</template></el-table-column>
      <el-table-column :label="$tx('Amount')" min-width="125"><template #default="{ row }">{{ row.currencyCode }} {{ Number(row.amount).toFixed(2) }}</template></el-table-column>
      <el-table-column :label="$tx('Provider')" prop="providerCode" min-width="100" />
      <el-table-column :label="$tx('Status')" min-width="145"><template #default="{ row }"><el-tag :type="row.resolutionStatusCode === 'refund_requested' ? 'warning' : 'info'">{{ exceptionLabel(row.resolutionStatusCode) }}</el-tag></template></el-table-column>
      <el-table-column :label="$tx('Created At')" min-width="160"><template #default="{ row }">{{ formatCustomerDate(row.createdAt) }}</template></el-table-column>
    </el-table>
    <p v-else class="finance-empty">{{ $tx('No payment exceptions') }}</p>
  </section>
</template>

<script setup>
import { ref, watch } from 'vue'
import { tx, localizeBackendMessage } from '@/i18n'
import { getOrderFinanceExceptions } from '@/api/nxr/orders'
import { formatCustomerDate } from '../../customers/customerPresentation'

const props = defineProps({ orderId: { type: Number, required: true }, active: { type: Boolean, default: false }, refreshKey: { type: Number, default: 0 } })
const rows = ref([]), loading = ref(false), error = ref('')
let generation = 0
const labels = { refund_requested: 'Refund requested', reconciliation_hold: 'Reconciliation hold', refunded: 'Refunded', reversed: 'Payment reversed', funds_verified: 'Funds verified', resolved: 'Resolved', open: 'Open', late_payment: 'Late payment', amount_mismatch: 'Payment amount mismatch', currency_mismatch: 'Payment currency mismatch', duplicate_payment: 'Duplicate payment', payment_after_cancel: 'Payment after cancellation' }
function exceptionLabel(code) { return tx(labels[code] || code || '-') }
async function load() {
  if (!props.active) return
  const current = ++generation
  rows.value = []
  loading.value = true
  error.value = ''
  try {
    const response = await getOrderFinanceExceptions(props.orderId)
    if (current === generation) rows.value = response.data || []
  } catch (failure) {
    if (current === generation) error.value = localizeBackendMessage(failure?.response?.data?.message || failure?.message) || tx('Unable to load payment exceptions')
  } finally { if (current === generation) loading.value = false }
}
watch(() => [props.orderId, props.active, props.refreshKey], () => {
  generation += 1
  rows.value = []
  error.value = ''
  loading.value = false
  if (props.active) void load()
}, { immediate: true })
</script>

<style scoped>
.finance-exceptions{margin-bottom:20px}
.finance-exceptions-heading{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px}
.finance-exception-details{margin:12px 20px}
.finance-empty{color:var(--nxr-text-muted);margin:0;padding:12px 0}
</style>
