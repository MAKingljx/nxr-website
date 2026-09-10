<template>
  <section class="workbench-panel">
    <div class="panel-heading">
      <div><strong>{{ $tx('Order Workbench') }}</strong><p>{{ $tx('Lock one order, then scan every physical card through intake, labeling, and packing.') }}</p></div>
      <el-button v-if="!snapshot?.activeSession" type="primary" :loading="starting" @click="start">{{ $tx('Start / lock order') }}</el-button>
      <el-tag v-else type="warning">{{ $tx('Locked by user') }} #{{ snapshot.activeSession.lockedByUserId }}</el-tag>
    </div>
    <el-alert v-if="snapshot?.workbenchRequired" :closable="false" type="info" show-icon :title="$tx('Return shipping is blocked until the order packing check passes.')" />
    <template v-if="snapshot?.activeSession">
      <el-form :inline="true" class="scan-row" @submit.prevent="scan">
        <el-form-item :label="$tx('Stage')"><el-select v-model="stage" style="width:140px"><el-option :label="$tx('Intake')" value="intake" /><el-option :label="$tx('Label')" value="label" /><el-option :label="$tx('Packing')" value="packing" /></el-select></el-form-item>
        <el-form-item :label="$tx(stage === 'intake' ? 'Physical barcode' : 'Current label barcode')"><el-input ref="barcodeInput" v-model="barcode" autofocus clearable style="width:320px" @keyup.enter="scan" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="scanning" @click="scan">{{ $tx('Scan') }}</el-button></el-form-item>
      </el-form>
    </template>
    <el-table v-if="snapshot?.items?.length" :data="snapshot.items" size="small" border class="mt12">
      <el-table-column label="#" prop="itemNo" width="54" />
      <el-table-column :label="$tx('Physical barcode')" prop="barcode" min-width="190" />
      <el-table-column :label="$tx('Current label barcode')" min-width="300"><template #default="scope"><template v-if="scope.row.labelBarcode"><code>{{ scope.row.labelBarcode }}</code><el-button link type="primary" @click="copyLabelBarcode(scope.row.labelBarcode)">{{ $tx('Copy') }}</el-button></template><span v-else>-</span></template></el-table-column>
      <el-table-column :label="$tx('Card')" prop="cardName" min-width="160" />
      <el-table-column :label="$tx('Result / Cert')" min-width="180"><template #default="scope">{{ scope.row.finalGradeLabel || scope.row.finalGradeValue || scope.row.vintageClassification || scope.row.merchDescription || scope.row.productType || '-' }} · {{ scope.row.certId || '-' }}</template></el-table-column>
      <el-table-column :label="$tx('Grading Record')" width="150"><template #default="scope"><el-button v-if="!scope.row.gradingSubmissionId && ownsActiveSession && scope.row.intakeScannedAt" v-hasPermi="['nxr:order:grading']" link type="primary" @click="openSubmission(scope.row)">{{ $tx('Create for card') }}</el-button><span v-else-if="!scope.row.gradingSubmissionId">{{ $tx('Scan intake first') }}</span><el-button v-else-if="scope.row.gradingStatusCode === 'pending'" v-hasPermi="['nxr:entry:approve']" link type="warning" :loading="approvingSubmissionId === scope.row.gradingSubmissionId" @click="approve(scope.row)">{{ $tx('Approve result') }}</el-button><el-tag v-else :type="['approved','published'].includes(scope.row.gradingStatusCode) ? 'success' : 'info'">{{ scope.row.gradingStatusCode }}</el-tag></template></el-table-column>
      <el-table-column :label="$tx('Intake')" width="80" align="center"><template #default="scope"><el-icon :color="scope.row.intakeScannedAt ? '#16a34a' : '#9ca3af'"><CircleCheck /></el-icon></template></el-table-column>
      <el-table-column :label="$tx('Label')" width="80" align="center"><template #default="scope"><el-icon :color="scope.row.labelScannedAt ? '#16a34a' : '#9ca3af'"><CircleCheck /></el-icon></template></el-table-column>
      <el-table-column :label="$tx('Packing')" width="80" align="center"><template #default="scope"><el-icon :color="scope.row.packingScannedAt ? '#16a34a' : '#9ca3af'"><CircleCheck /></el-icon></template></el-table-column>
    </el-table>
    <div class="panel-actions">
      <el-input v-model="reprintReason" clearable :placeholder="$tx('Reason required for every reprint')" />
      <el-button :loading="exporting === 'labels'" @click="download('labels')">{{ $tx('Export order labels') }}</el-button>
      <el-button :loading="exporting === 'manifest'" @click="download('manifest')">{{ $tx('Export packing manifest') }}</el-button>
      <el-button v-if="snapshot?.activeSession" type="success" :loading="checking" @click="packingCheck">{{ $tx('Complete packing check') }}</el-button>
      <el-tag v-if="snapshot?.packingCheck?.statusCode === 'passed' && !snapshot.packingCheck.invalidatedAt" type="success">{{ $tx('Packing verified') }}</el-tag>
    </div>
    <p v-if="snapshot?.printJobs?.length" class="print-history">{{ $tx('Recorded exports') }}: {{ snapshot.printJobs.length }} · {{ $tx('Latest') }} {{ snapshot.printJobs[0].exportTypeCode }} #{{ snapshot.printJobs[0].printSequence }}</p>

    <el-dialog v-model="submissionOpen" :title="$tx('Create Grading Record for This Card')" width="min(720px, calc(100vw - 32px))" append-to-body>
      <el-alert type="info" :closable="false" show-icon :title="$tx('This record is created inside the locked order and cannot be linked to another order.')" />
      <el-form :model="submissionForm" label-position="top" class="submission-form mt12">
        <el-row :gutter="12">
          <el-col :xs="24" :sm="12"><el-form-item :label="$tx('Product Type')" required><el-select v-model="submissionForm.productType"><el-option :label="$tx('Graded Card')" value="graded_card" /><el-option :label="$tx('Merch Product')" value="merch_product" /><el-option :label="$tx('Vintage Product')" value="vintage_product" /></el-select></el-form-item></el-col>
          <el-col :xs="24" :sm="12"><el-form-item :label="$tx('Certificate ID')" required><el-input v-model="submissionForm.certId" maxlength="32" /></el-form-item></el-col>
          <el-col v-if="isGradedSubmission" :xs="24" :sm="12"><el-form-item :label="$tx('Category')"><el-select v-model="submissionForm.cardCategory"><el-option :label="$tx('Trading Card')" value="trading_card" /><el-option :label="$tx('Sports Card')" value="sports_card" /><el-option :label="$tx('Celebrity Card')" value="celebrity_card" /><el-option :label="$tx('Movie Film')" value="movie_film" /></el-select></el-form-item></el-col>
          <el-col v-if="submissionForm.productType === 'merch_product'" :span="24"><el-form-item :label="$tx('Merch Description')"><el-input v-model="submissionForm.merchDescription" type="textarea" :rows="3" maxlength="4000" /></el-form-item></el-col>
          <el-col v-if="submissionForm.productType === 'vintage_product'" :xs="24" :sm="12"><el-form-item :label="$tx('Vintage Classification')" required><el-select v-model="submissionForm.vintageClassification"><el-option v-for="item in nxr_vintage_classification" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item></el-col>
          <template v-if="isGradedSubmission && submissionForm.cardCategory === 'movie_film'">
            <el-col :xs="24" :sm="12"><el-form-item :label="$tx('Movie Name')" required><el-input v-model="submissionForm.movieName" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item :label="$tx('Release Year')" required><el-input v-model="submissionForm.releaseYear" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item :label="$tx('Production Company')" required><el-input v-model="submissionForm.productionCompany" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item :label="$tx('Film Type')" required><el-input v-model="submissionForm.filmType" /></el-form-item></el-col>
          </template>
          <template v-else>
            <el-col :xs="24" :sm="12"><el-form-item :label="$tx('Card Name')" required><el-input v-model="submissionForm.cardName" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item :label="$tx('Brand')" required><el-input v-model="submissionForm.brandName" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item :label="$tx('Set Name')" required><el-input v-model="submissionForm.setName" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item :label="$tx('Card Number')" required><el-input v-model="submissionForm.cardNumber" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item :label="$tx('Year')"><el-input v-model="submissionForm.yearLabel" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item :label="$tx('Language')" required><el-input v-model="submissionForm.languageCode" maxlength="32" /></el-form-item></el-col>
            <el-col v-if="submissionForm.cardCategory === 'sports_card'" :xs="24" :sm="12"><el-form-item :label="$tx('Sport')" required><el-input v-model="submissionForm.sportsType" /></el-form-item></el-col>
            <el-col v-if="submissionForm.cardCategory === 'celebrity_card'" :xs="24" :sm="12"><el-form-item :label="$tx('Group Name')" required><el-input v-model="submissionForm.groupName" /></el-form-item></el-col>
          </template>
          <el-col v-for="field in isGradedSubmission ? scoreFields : []" :key="field.key" :xs="12" :sm="6"><el-form-item :label="$tx(field.label)" required><el-input-number v-model="submissionForm[field.key]" :min="1" :max="10" :step="0.5" :precision="1" /></el-form-item></el-col>
          <el-col :span="24"><el-form-item :label="$tx('Entry Notes')"><el-input v-model="submissionForm.entryNotes" type="textarea" :rows="2" maxlength="2000" /></el-form-item></el-col>
        </el-row>
      </el-form>
      <template #footer><el-button @click="submissionOpen=false">{{ $tx('Cancel') }}</el-button><el-button type="primary" :loading="creatingSubmission" @click="createSubmission">{{ $tx('Create & Link') }}</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, getCurrentInstance, nextTick, ref, watch } from 'vue'
import { saveAs } from 'file-saver'
import { ElMessage } from 'element-plus'
import { approveSubmission } from '@/api/nxr/entries'
import useUserStore from '@/store/modules/user'
import {
  completeOrderPackingCheck, createOrderItemSubmission, exportOrderLabels, exportOrderManifest,
  getOrderWorkbench, scanOrderWorkbench, startOrderWorkbench
} from '@/api/nxr/orderWorkbench'

const props = defineProps({ orderId: { type: Number, required: true }, orderItems: { type: Array, default: () => [] } })
const { proxy } = getCurrentInstance()
const { nxr_vintage_classification } = proxy.useDict('nxr_vintage_classification')
const snapshot = ref(null)
const userStore = useUserStore()
const ownsActiveSession = computed(() => Number(snapshot.value?.activeSession?.lockedByUserId) === Number(userStore.id))
const isGradedSubmission = computed(() => submissionForm.value.productType === 'graded_card')
const stage = ref('intake')
const barcode = ref('')
const reprintReason = ref('')
const barcodeInput = ref(null)
const starting = ref(false)
const scanning = ref(false)
const checking = ref(false)
const exporting = ref('')
const submissionOpen = ref(false)
const creatingSubmission = ref(false)
const approvingSubmissionId = ref(null)
const activeWorkbenchItem = ref(null)
const scoreFields = [{ key: 'centeringScore', label: 'Centering' }, { key: 'edgesScore', label: 'Edges' }, { key: 'cornersScore', label: 'Corners' }, { key: 'surfaceScore', label: 'Surface' }]
const submissionForm = ref(emptySubmission())

function emptySubmission() {
  return { certId: '', productType: 'graded_card', vintageClassification: '', merchDescription: '', cardCategory: 'trading_card', cardName: '', movieName: '', releaseYear: '', productionCompany: '', filmType: '', sportsType: '', groupName: '', yearLabel: '', brandName: '', setName: '', cardNumber: '', languageCode: 'EN', centeringScore: 10, edgesScore: 10, cornersScore: 10, surfaceScore: 10, entryNotes: '' }
}
function normalizeProductType(value) { return value === 'merch_product' || value === 'label_product' ? 'merch_product' : value === 'vintage_product' ? 'vintage_product' : 'graded_card' }
function openSubmission(item) {
  activeWorkbenchItem.value = item
  const source = props.orderItems.find(row => row.id === item.orderItemId) || {}
  submissionForm.value = { ...emptySubmission(), productType: normalizeProductType(source.productType), cardName: source.cardName || item.cardName || '', brandName: source.brandName || '', yearLabel: source.year || '', setName: source.setName || '', cardNumber: source.cardNumber || '', languageCode: source.languageCode || 'EN', cardCategory: source.category || 'trading_card' }
  submissionOpen.value = true
}
async function createSubmission() {
  if (!activeWorkbenchItem.value || !submissionForm.value.certId.trim()) return
  creatingSubmission.value = true
  try {
    await createOrderItemSubmission(props.orderId, activeWorkbenchItem.value.orderItemId, submissionForm.value)
    submissionOpen.value = false
    await load()
  } finally { creatingSubmission.value = false }
}
async function approve(item) {
  approvingSubmissionId.value = item.gradingSubmissionId
  try { await approveSubmission(item.gradingSubmissionId); await load() }
  finally { approvingSubmissionId.value = null }
}

async function load() {
  if (!props.orderId) return
  snapshot.value = (await getOrderWorkbench(props.orderId)).data
}
async function start() {
  starting.value = true
  try { snapshot.value = (await startOrderWorkbench(props.orderId)).data; await nextTick(); barcodeInput.value?.focus() } finally { starting.value = false }
}
async function scan() {
  if (!barcode.value.trim() || !snapshot.value?.activeSession) return
  scanning.value = true
  try {
    snapshot.value = (await scanOrderWorkbench(props.orderId, { sessionId: snapshot.value.activeSession.id, stage: stage.value, barcode: barcode.value.trim() })).data
    barcode.value = ''
    await nextTick(); barcodeInput.value?.focus()
  } finally { scanning.value = false }
}
async function copyLabelBarcode(value) {
  await navigator.clipboard.writeText(value)
  ElMessage.success('Copied')
}
async function packingCheck() {
  if (!snapshot.value?.activeSession) return
  checking.value = true
  try { snapshot.value = (await completeOrderPackingCheck(props.orderId, { sessionId: snapshot.value.activeSession.id })).data } finally { checking.value = false }
}
async function download(type) {
  exporting.value = type
  try {
    const call = type === 'labels' ? exportOrderLabels : exportOrderManifest
    const blob = await call(props.orderId, { reprintReason: reprintReason.value })
    saveAs(new Blob([blob], { type: 'text/csv;charset=UTF-8' }), `nxr-order-${props.orderId}-${type}.csv`)
    reprintReason.value = ''
    await load()
  } finally { exporting.value = '' }
}

watch(() => props.orderId, load, { immediate: true })
</script>

<style scoped>
.workbench-panel{display:grid;gap:12px}.panel-heading{display:flex;justify-content:space-between;align-items:center;gap:16px}.panel-heading p,.print-history{margin:4px 0 0;color:var(--nxr-text-faint)}.scan-row{margin-top:4px}.panel-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.panel-actions .el-input{width:min(300px,100%)}
@media(max-width:760px){.panel-heading{align-items:stretch;flex-direction:column}.panel-actions>*{width:100%!important}}
</style>
