<template>
  <section class="finance-exceptions" :aria-label="$tx('Payment exceptions')">
    <div class="finance-exceptions-heading">
      <strong>{{ $tx('Payment exceptions') }}</strong>
      <el-button link type="primary" :loading="loading" :disabled="saving" @click="load">{{ $tx('Refresh') }}</el-button>
    </div>
    <el-alert v-if="error" type="error" :closable="false" :title="error" />
    <template v-else>
      <div v-if="needsReview" class="finance-review">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item :label="$tx('Order total')">{{ amountLabel(reviewContext) }}</el-descriptions-item>
          <el-descriptions-item :label="$tx('Resume to')">{{ progressLabel(reviewContext.resumeStatusCode) }}</el-descriptions-item>
          <el-descriptions-item :label="$tx('Pending exceptions')">{{ reviewContext.pendingExceptionIds.length }}</el-descriptions-item>
          <el-descriptions-item :label="$tx('Order Status')">{{ progressLabel(reviewContext.statusCode) }}</el-descriptions-item>
        </el-descriptions>
        <div class="finance-review-actions">
          <el-button type="success" :disabled="busy || !reviewContext.canRestore" @click="openReview('restore')">{{ $tx('Verify payment and resume') }}</el-button>
          <el-button type="danger" :disabled="busy || !reviewContext.canCancel" @click="openReview('cancel')">{{ $tx('Confirm refund and cancel') }}</el-button>
          <el-button :disabled="busy || !actionAllowed('manual_review', reviewContext)" @click="openReview('manual_review')">{{ $tx('Keep paused for manual review') }}</el-button>
        </div>
        <p v-if="!reviewContext.canRestore && reviewContext.restoreBlockReason" class="finance-block-reason">{{ $tx('Cannot resume: {reason}', { reason: localizeBackendMessage(reviewContext.restoreBlockReason) }) }}</p>
        <p v-if="!reviewContext.canCancel && reviewContext.cancelBlockReason" class="finance-block-reason">{{ $tx('Cannot cancel: {reason}', { reason: localizeBackendMessage(reviewContext.cancelBlockReason) }) }}</p>
      </div>
      <el-table v-if="rows.length || loading" v-loading="loading" :data="rows" size="small" border>
        <el-table-column type="expand">
          <template #default="{ row }">
            <el-descriptions :column="2" border class="finance-exception-details">
              <el-descriptions-item :label="$tx('Transaction ID')">{{ row.providerTransactionId || '-' }}</el-descriptions-item>
              <el-descriptions-item :label="$tx('Provider event')">{{ row.providerEventId || '-' }}</el-descriptions-item>
              <el-descriptions-item :label="$tx('Evidence reference')">{{ row.evidenceReference || '-' }}</el-descriptions-item>
              <el-descriptions-item :label="$tx('Review action')">{{ actionLabel(row.resolutionAction) }}</el-descriptions-item>
              <el-descriptions-item :label="$tx('Processing Note')" :span="2">{{ row.resolutionNote || '-' }}</el-descriptions-item>
              <el-descriptions-item :label="$tx('Reviewed by')">{{ row.resolvedByUserId ?? '-' }}</el-descriptions-item>
              <el-descriptions-item :label="$tx('Resolved at')">{{ formatCustomerDate(row.resolvedAt) }}</el-descriptions-item>
            </el-descriptions>
          </template>
        </el-table-column>
        <el-table-column :label="$tx('Exception')" min-width="160"><template #default="{ row }">{{ exceptionLabel(row.exceptionTypeCode) }}</template></el-table-column>
        <el-table-column :label="$tx('Amount')" min-width="125"><template #default="{ row }">{{ amountLabel(row) }}</template></el-table-column>
        <el-table-column :label="$tx('Provider')" prop="providerCode" min-width="100" />
        <el-table-column :label="$tx('Status')" min-width="145"><template #default="{ row }"><el-tag :type="['open', 'reconciliation_hold', 'refund_requested', 'manual_review'].includes(row.resolutionStatusCode) ? 'warning' : 'success'">{{ exceptionLabel(row.resolutionStatusCode) }}</el-tag></template></el-table-column>
        <el-table-column :label="$tx('Created At')" min-width="160"><template #default="{ row }">{{ formatCustomerDate(row.createdAt) }}</template></el-table-column>
      </el-table>
      <p v-else class="finance-empty">{{ $tx('No payment exceptions') }}</p>
    </template>

    <el-dialog v-model="dialogOpen" :title="actionLabel(reviewAction)" width="min(580px, calc(100vw - 32px))" append-to-body :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <template v-if="dialogContext">
        <el-descriptions :column="1" border class="finance-dialog-summary">
          <el-descriptions-item :label="$tx('Order total')">{{ amountLabel(dialogContext) }}</el-descriptions-item>
          <el-descriptions-item v-if="reviewAction === 'restore'" :label="$tx('Resume to')">{{ progressLabel(dialogContext.resumeStatusCode) }}</el-descriptions-item>
          <el-descriptions-item :label="$tx('Pending exceptions')">{{ dialogContext.pendingExceptionIds.length }}</el-descriptions-item>
        </el-descriptions>
        <el-alert :title="actionHint" :type="reviewAction === 'cancel' ? 'warning' : 'info'" :closable="false" show-icon class="finance-dialog-summary" />
        <el-alert v-if="dialogBlockReason || submitError" :title="dialogBlockReason || submitError" type="error" :closable="false" class="finance-dialog-summary" />
        <el-form :model="reviewForm" label-position="top" class="finance-review-form" @submit.prevent="submitReview">
          <el-form-item :label="$tx('Evidence reference')" required>
            <el-input v-model="reviewForm.evidenceReference" :disabled="saving" maxlength="255" :placeholder="$tx('Payment or refund transaction reference')" />
          </el-form-item>
          <el-form-item :label="$tx('Review reason')" required>
            <el-input v-model="reviewForm.reason" :disabled="saving" type="textarea" :rows="3" maxlength="1000" show-word-limit />
          </el-form-item>
          <el-checkbox v-if="reviewAction !== 'manual_review'" v-model="reviewForm.confirmed" :disabled="saving">
            {{ reviewAction === 'restore' ? $tx('I verified receipt of the full order amount: {amount}', { amount: amountLabel(dialogContext) }) : $tx('I verified that the refund has been completed') }}
          </el-checkbox>
        </el-form>
      </template>
      <template #footer>
        <el-button :disabled="saving || loading" @click="refreshReview">{{ $tx('Refresh review') }}</el-button>
        <el-button :disabled="saving" @click="dialogOpen = false">{{ $tx('Close') }}</el-button>
        <el-button :type="reviewAction === 'cancel' ? 'danger' : 'primary'" :loading="saving" :disabled="!canSubmit" @click="submitReview">{{ actionLabel(reviewAction) }}</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { activeLocale, tx, localizeBackendMessage } from '@/i18n'
import { getOrderFinanceExceptions, getOrderFinanceReviewContext, reviewOrderFinanceExceptions } from '@/api/nxr/orders'
import { formatCustomerDate } from '../../customers/customerPresentation'

const props = defineProps({ orderId: { type: Number, required: true }, active: { type: Boolean, default: false }, refreshKey: { type: Number, default: 0 } })
const emit = defineEmits(['reviewed'])
const rows = ref([]), reviewContext = ref(null), loading = ref(false), error = ref('')
const dialogOpen = ref(false), dialogContext = ref(null), reviewAction = ref('manual_review'), saving = ref(false), submitError = ref('')
const reviewForm = reactive({ evidenceReference: '', reason: '', confirmed: false })
let generation = 0, scopeGeneration = 0, dialogGeneration = 0, disposed = false
const busy = computed(() => loading.value || saving.value)
const needsReview = computed(() => reviewContext.value && (reviewContext.value.statusCode === 'payment_exception' || reviewContext.value.pendingExceptionIds.length > 0))
const labels = { refund_requested: 'Refund requested', reconciliation_hold: 'Reconciliation hold', refunded: 'Refunded', reversed: 'Payment reversed', funds_verified: 'Funds verified', resolved: 'Resolved', open: 'Open', late_payment: 'Late payment', amount_mismatch: 'Payment amount mismatch', currency_mismatch: 'Payment currency mismatch', duplicate_payment: 'Duplicate payment', payment_after_cancel: 'Payment after cancellation', manual_review: 'Manual financial review', refund_verified: 'Refund verified' }
const actions = { restore: 'Verify payment and resume', cancel: 'Confirm refund and cancel', manual_review: 'Keep paused for manual review' }
const progress = { admission_review: 'Application Review', terms_confirmation: 'Awaiting Terms Confirmation', payment_expired: 'Payment Deadline Expired', awaiting_payment: 'Awaiting Payment', payment_review: 'Payment Review', payment_exception: 'Payment Exception', awaiting_inbound: 'Awaiting Cards', inbound_shipped: 'Shipped to NXR', intake_exception: 'Intake Exception', received: 'Cards Received', grading: 'Grading', review: 'Review', quality_check: 'Quality Check', quality_hold: 'QC Rework', completed: 'Ready to Return', return_shipped: 'Return Shipped', delivered: 'Delivered', cancelled: 'Cancelled' }
function exceptionLabel(code) { return tx(labels[code] || code || '-') }
function actionLabel(action) { return tx(actions[action] || action || '-') }
function progressLabel(status) { return tx(progress[status] || status || '-') }
function amountLabel(value) {
  const amount = Number(value?.amount)
  const decimals = value?.currencyCode === 'JPY' ? 0 : 2
  return `${value?.currencyCode || ''} ${Number.isFinite(amount) ? new Intl.NumberFormat(activeLocale(), { minimumFractionDigits: decimals, maximumFractionDigits: decimals }).format(amount) : '-'}`.trim()
}
function failureMessage(failure) { return localizeBackendMessage(failure?.response?.data?.message || failure?.response?.data?.msg || failure?.message) || tx('Unable to complete the financial review') }
function currentScope(scope, orderId) { return !disposed && scope === scopeGeneration && props.active && props.orderId === orderId }
function normalizeContext(value, orderId) {
  if (!value || value.orderId !== orderId || !Array.isArray(value.pendingExceptionIds)) throw new Error(tx('Unable to load the financial review details'))
  return { ...value, pendingExceptionIds: [...value.pendingExceptionIds] }
}

async function load() {
  if (!props.active || !props.orderId || disposed) return
  const current = ++generation, scope = scopeGeneration, orderId = props.orderId
  rows.value = []
  reviewContext.value = null
  loading.value = true
  error.value = ''
  try {
    const [listResponse, contextResponse] = await Promise.all([getOrderFinanceExceptions(orderId), getOrderFinanceReviewContext(orderId)])
    if (current === generation && currentScope(scope, orderId)) {
      reviewContext.value = normalizeContext(contextResponse.data, orderId)
      rows.value = listResponse.data || []
    }
  } catch (failure) {
    if (current === generation && currentScope(scope, orderId)) error.value = failureMessage(failure)
  } finally { if (current === generation) loading.value = false }
}

function actionAllowed(action, context) {
  if (!context || context.statusCode !== 'payment_exception' || !context.pendingExceptionIds.length) return false
  if (action === 'restore') return context.canRestore === true
  if (action === 'cancel') return context.canCancel === true
  return action === 'manual_review' && (context.statusCode === 'payment_exception' || context.pendingExceptionIds.length > 0)
}
const actionHint = computed(() => tx({ restore: 'Record verified full payment and resume the order at its previous progress.', cancel: 'Record a completed refund and cancel the order.', manual_review: 'Record the evidence and keep fulfillment paused for manual review.' }[reviewAction.value]))
const dialogBlockReason = computed(() => {
  if (!dialogOpen.value || !dialogContext.value) return ''
  if (dialogContext.value.orderId !== props.orderId || !props.active || disposed) return tx('The review details changed. Refresh the review before submitting.')
  if (loading.value) return ''
  if (!reviewContext.value || dialogGeneration !== generation) return tx('The review details changed. Refresh the review before submitting.')
  if (actionAllowed(reviewAction.value, reviewContext.value)) return ''
  const reason = reviewAction.value === 'restore' ? reviewContext.value.restoreBlockReason : reviewContext.value.cancelBlockReason
  return localizeBackendMessage(reason) || tx('This action is no longer available. Refresh the review.')
})
const canSubmit = computed(() => dialogOpen.value && !busy.value && !dialogBlockReason.value && Boolean(reviewForm.evidenceReference.trim()) && Boolean(reviewForm.reason.trim()) && reviewForm.evidenceReference.trim().length <= 255 && reviewForm.reason.trim().length <= 1000 && (reviewAction.value === 'manual_review' || reviewForm.confirmed))

function openReview(action) {
  if (busy.value || !props.active || reviewContext.value?.orderId !== props.orderId || !actionAllowed(action, reviewContext.value)) return
  reviewAction.value = action
  dialogContext.value = normalizeContext(reviewContext.value, props.orderId)
  dialogGeneration = generation
  Object.assign(reviewForm, { evidenceReference: '', reason: '', confirmed: false })
  submitError.value = ''
  dialogOpen.value = true
}
async function refreshReview() {
  if (saving.value || !dialogOpen.value) return
  const scope = scopeGeneration, orderId = props.orderId
  await load()
  if (dialogOpen.value && currentScope(scope, orderId) && reviewContext.value) {
    dialogContext.value = normalizeContext(reviewContext.value, orderId)
    dialogGeneration = generation
    reviewForm.confirmed = false
    submitError.value = ''
  }
}
async function submitReview() {
  if (!canSubmit.value) return
  const scope = scopeGeneration, orderId = props.orderId, context = dialogContext.value
  const payload = { action: reviewAction.value, exceptionIds: [...context.pendingExceptionIds], evidenceReference: reviewForm.evidenceReference.trim(), reason: reviewForm.reason.trim() }
  if (payload.action === 'restore') {
    const amount = Number(context.amount)
    if (!Number.isFinite(amount) || !context.currencyCode) { submitError.value = tx('Unable to load the financial review details'); return }
    payload.amount = amount
    payload.currencyCode = context.currencyCode
  }
  saving.value = true
  submitError.value = ''
  try {
    const response = await reviewOrderFinanceExceptions(orderId, payload)
    if (currentScope(scope, orderId)) {
      const updated = normalizeContext(response.data, orderId)
      generation += 1
      reviewContext.value = updated
      error.value = ''
      loading.value = false
      dialogOpen.value = false
      emit('reviewed', orderId)
    } else if (!disposed && props.active && props.orderId === orderId) {
      // A reopened view needs a fresh read, rather than the prior view's response.
      const reopenedScope = scopeGeneration
      await load()
      if (currentScope(reopenedScope, orderId) && reviewContext.value && !error.value) emit('reviewed', orderId)
    }
  } catch (failure) {
    if (currentScope(scope, orderId)) submitError.value = failureMessage(failure)
  } finally { saving.value = false }
}
watch(() => [props.orderId, props.active, props.refreshKey], (values, previous) => {
  if (!previous || values[0] !== previous[0] || values[1] !== previous[1]) {
    scopeGeneration += 1
    dialogOpen.value = false
    dialogContext.value = null
    submitError.value = ''
    Object.assign(reviewForm, { evidenceReference: '', reason: '', confirmed: false })
  }
  generation += 1
  rows.value = []
  reviewContext.value = null
  error.value = ''
  loading.value = false
  if (props.active) void load()
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { disposed = true; generation += 1; scopeGeneration += 1 })
</script>

<style scoped>
.finance-exceptions{margin-bottom:20px}
.finance-exceptions-heading{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px}
.finance-exception-details{margin:12px 20px}
.finance-review{margin-bottom:16px}
.finance-review-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}
.finance-review-actions .el-button{margin-left:0}
.finance-block-reason,.finance-empty{color:var(--nxr-text-muted);margin:0;padding:8px 0;line-height:1.5}
.finance-dialog-summary{margin-bottom:14px}
.finance-review-form :deep(.el-checkbox){height:auto;align-items:flex-start}
.finance-review-form :deep(.el-checkbox__label){white-space:normal;line-height:1.5}
</style>
