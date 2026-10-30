<template>
  <section v-loading="loading" data-testid="partner-submit-admission">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="review?.eligible">
      <el-divider content-position="left">{{ $tx('Submit review') }}</el-divider>
      <el-table :data="reviewRows" size="small" border>
        <el-table-column :label="$tx('Order No.')" min-width="150"><template #default="{ row }"><el-button link type="primary" @click="$emit('open-order', row.orderId)">{{ row.orderNo }}</el-button></template></el-table-column>
        <el-table-column :label="$tx('Customer label')" prop="clientDisplayName" min-width="120" />
        <el-table-column :label="$tx('Cards')" prop="totalCardCount" width="75" />
        <el-table-column :label="$tx('Status')" min-width="180"><template #default="{ row }">{{ statusLabel(row.admissionStatus) }}<div v-if="submitDecisionReason(row)" class="decision-reason"><strong>{{ $tx('Review note') }}：</strong>{{ submitDecisionReason(row) }}</div></template></el-table-column>
      </el-table>
      <template v-if="canDecide">
        <div class="decision-actions">
          <el-button type="warning" :disabled="!!saving" :loading="saving === 'request_information'" @click="decide('request_information')">{{ $tx('Request information') }}</el-button>
          <el-button type="danger" :disabled="!!saving" :loading="saving === 'reject'" @click="decide('reject')">{{ $tx('Reject application') }}</el-button>
          <el-button type="success" :disabled="!!saving" :loading="saving === 'approve'" @click="decide('approve')">{{ $tx('Approve application') }}</el-button>
        </div>
      </template>
      <p v-else class="review-status">{{ $tx('All orders must be pending review before this Submit can be reviewed.') }}</p>
    </template>
    <el-button v-if="error" link type="primary" @click="load">{{ $tx('Retry') }}</el-button>
  </section>
</template>

<script setup>
import { computed, getCurrentInstance, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { localizeBackendMessage, tx } from '@/i18n'
import { decidePartnerSubmitAdmission, getPartnerSubmitAdmission } from '@/api/nxr/orderAdmission'
import { buildSubmitDecision, canReviewSubmit, joinSubmitReviewOrders, submitDecisionReason } from '../submitAdmission'

const props = defineProps({ batchId: { type: Number, required: true }, orders: { type: Array, default: () => [] } })
const emit = defineEmits(['changed', 'open-order'])
const { proxy } = getCurrentInstance()
const review = ref(null), loading = ref(false), saving = ref(''), error = ref('')
let generation = 0
const reviewRows = computed(() => joinSubmitReviewOrders(props.orders, review.value?.orders))
const canDecide = computed(() => canReviewSubmit(props.orders, review.value))
function statusLabel(value) { return tx(({ pending_review: 'Pending review', approved: 'Approved', rejected: 'Rejected', needs_information: 'Needs information' })[value] || value || 'Not available') }
async function load() {
  const current = ++generation, batchId = props.batchId
  loading.value = true; error.value = ''; review.value = null
  try { const result = await getPartnerSubmitAdmission(batchId); if (current === generation) review.value = result.data }
  catch (e) { if (current === generation) error.value = localizeBackendMessage(e?.response?.data?.message || e?.response?.data?.msg || e?.message || tx('Unable to load Submit review.')) }
  finally { if (current === generation) loading.value = false }
}
async function decide(decision) {
  if (!canDecide.value || saving.value) return
  const batchId = props.batchId, current = generation, snapshot = buildSubmitDecision(review.value, decision, '')
  saving.value = decision
  try {
    try {
      if (decision === 'approve') {
        await proxy.$modal.confirm(tx('Apply this decision to all orders in this Submit?'))
      } else {
        const result = await ElMessageBox.prompt(tx('Enter the reason for all orders in this Submit.'), tx(decision === 'reject' ? 'Reject application' : 'Request information'), {
          inputType: 'textarea', inputAttributes: { maxlength: 2000 },
          inputValidator: value => Boolean(value?.trim()) || tx('Enter a review note'),
          confirmButtonText: tx('Confirm'), cancelButtonText: tx('Cancel')
        })
        snapshot.note = result.value.trim()
        if (!snapshot.note || snapshot.note.length > 2000) return
      }
    } catch { return }
    if (current !== generation || batchId !== props.batchId || !canDecide.value) return
    await decidePartnerSubmitAdmission(batchId, snapshot)
    proxy.$modal.msgSuccess(tx('Admission decision saved')); emit('changed')
  } catch (e) {
    error.value = localizeBackendMessage(e?.response?.data?.message || e?.response?.data?.msg || e?.message || tx('Unable to save Submit review.'))
    if (e?.response?.status === 409 || e?.status === 409) { proxy.$modal.msgWarning(error.value); emit('changed') }
  } finally { saving.value = '' }
}
watch(() => props.batchId, () => { void load() }, { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>

<style scoped>
.decision-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:16px}.decision-actions .el-button{margin-left:0}.review-status{color:var(--nxr-text-faint);margin-top:12px}.decision-reason{white-space:pre-wrap;overflow-wrap:anywhere;margin-top:4px}
</style>
