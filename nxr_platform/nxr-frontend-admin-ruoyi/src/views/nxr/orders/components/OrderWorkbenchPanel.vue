<template>
  <section class="workbench-panel">
    <div v-if="!orderReturnMode" class="panel-heading">
      <div><strong>{{ $tx('Order Workbench') }}</strong><p>{{ $tx('Lock one order, then scan every physical card through intake, labeling, and packing.') }}</p></div>
      <el-button v-if="!snapshot?.activeSession" type="primary" :loading="starting" @click="start">{{ $tx('Start / lock order') }}</el-button>
      <el-tag v-else type="warning">{{ $tx('Locked by user') }} #{{ snapshot.activeSession.lockedByUserId }}</el-tag>
    </div>
    <NxrCardIdentityPanel v-if="!orderReturnMode" :order-id="orderId" compact @loaded="loadIdentities" />
    <el-alert v-if="!orderReturnMode && snapshot?.workbenchRequired" :closable="false" type="info" show-icon :title="$tx(isPartnerOrder ? 'For partial returns, export and scan the selected cards, then finish their return checks.' : 'Return shipping is blocked until the order packing check passes.')" />
    <template v-if="!orderReturnMode && snapshot?.activeSession">
      <el-form :inline="true" class="scan-row" @submit.prevent="scan">
        <el-form-item :label="$tx('Stage')"><el-select v-model="stage" style="width:140px"><el-option :label="$tx('Intake')" value="intake" /><el-option :label="$tx('Label')" value="label" /><el-option :label="$tx('Packing')" value="packing" /></el-select></el-form-item>
        <el-form-item :label="$tx(stage === 'intake' ? 'Physical barcode' : 'Current label barcode')"><el-input ref="barcodeInput" v-model="barcode" autofocus clearable style="width:320px" @keyup.enter="scan" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="scanning" @click="scan">{{ $tx('Scan') }}</el-button></el-form-item>
      </el-form>
    </template>
    <el-table v-if="displayItems.length" :data="displayItems" size="small" border class="mt12" row-key="orderItemId" @selection-change="selectedLabelItems=$event">
      <el-table-column v-if="!orderReturnMode && isPartnerOrder" type="selection" width="48" :selectable="row=>['approved','published'].includes(row.gradingStatusCode)" />
      <el-table-column label="#" prop="itemNo" width="54" />
      <el-table-column v-if="!orderReturnMode" :label="$tx('Intake code')" min-width="220"><template #default="scope"><code>{{identities[scope.row.orderItemId]?.receiptCode||scope.row.barcode}}</code></template></el-table-column>
      <el-table-column v-if="!orderReturnMode" :label="$tx('Original customer')" min-width="150"><template #default="scope">{{identities[scope.row.orderItemId]?.ownerDisplayName||'—'}}<small v-if="identities[scope.row.orderItemId]?.clientReference"> · {{identities[scope.row.orderItemId].clientReference}}</small></template></el-table-column>
      <el-table-column v-if="!orderReturnMode" :label="$tx('Submission source')" min-width="170"><template #default="scope">{{identities[scope.row.orderItemId]?.sourceType==='partner'?$tx('Partner: ')+identities[scope.row.orderItemId].partnerCompanyName:identities[scope.row.orderItemId]?$tx('Customer direct to NXR'):'—'}}</template></el-table-column>
      <el-table-column v-if="!orderReturnMode" :label="$tx('Current label barcode')" min-width="300"><template #default="scope"><template v-if="scope.row.labelBarcode"><code>{{ scope.row.labelBarcode }}</code><el-button link type="primary" @click="copyLabelBarcode(scope.row.labelBarcode)">{{ $tx('Copy') }}</el-button></template><span v-else>-</span></template></el-table-column>
      <el-table-column :label="$tx('Card')" prop="cardName" min-width="160" />
      <el-table-column :label="$tx('Result / Cert')" min-width="180"><template #default="scope">{{ scope.row.finalGradeLabel || scope.row.finalGradeValue || scope.row.vintageClassification || scope.row.merchDescription || scope.row.productType || '-' }} · {{ scope.row.certId || '-' }}</template></el-table-column>
      <el-table-column :label="$tx('Grading Record')" min-width="190"><template #default="scope"><template v-if="!scope.row.gradingSubmissionId"><el-button v-if="orderReturnMode || (ownsActiveSession && scope.row.intakeScannedAt)" v-hasPermi="['nxr:order:grading']" link type="primary" :disabled="orderReturnMode && Boolean(partnerGradingBlock(orderStatus, scope.row.statusCode))" :data-testid="`order-grade-card-${scope.row.orderItemId}`" @click="openSubmission(scope.row)">{{ $tx('Create for card') }}</el-button><span v-else>{{ $tx('Scan intake first') }}</span><small v-if="orderReturnMode && partnerGradingBlock(orderStatus, scope.row.statusCode)" class="grading-block">{{ $tx(partnerGradingBlock(orderStatus, scope.row.statusCode)) }}</small></template><el-button v-else-if="scope.row.gradingStatusCode === 'pending'" v-hasPermi="['nxr:entry:approve']" link type="warning" :loading="approvingSubmissionId === scope.row.gradingSubmissionId" @click="approve(scope.row)">{{ $tx('Approve result') }}</el-button><el-tag v-else :type="['approved','published'].includes(scope.row.gradingStatusCode) ? 'success' : 'info'">{{ $tx(({ pending: 'Pending', approved: 'Approved', published: 'Published', rejected: 'Rejected', draft: 'Draft', cancelled: 'Cancelled' })[scope.row.gradingStatusCode] || scope.row.gradingStatusCode) }}</el-tag></template></el-table-column>
      <el-table-column v-if="!orderReturnMode" :label="$tx('Intake')" width="80" align="center"><template #default="scope"><el-icon :color="scope.row.intakeScannedAt ? '#16a34a' : '#9ca3af'"><CircleCheck /></el-icon></template></el-table-column>
      <el-table-column v-if="!orderReturnMode" :label="$tx('Label')" width="80" align="center"><template #default="scope"><el-icon :color="scope.row.labelScannedAt ? '#16a34a' : '#9ca3af'"><CircleCheck /></el-icon></template></el-table-column>
      <el-table-column v-if="!orderReturnMode" :label="$tx('Packing')" width="80" align="center"><template #default="scope"><el-icon :color="scope.row.packingScannedAt ? '#16a34a' : '#9ca3af'"><CircleCheck /></el-icon></template></el-table-column>
    </el-table>
    <div v-if="!orderReturnMode" class="panel-actions">
      <el-input v-model="reprintReason" clearable :placeholder="$tx('Reason required for every reprint')" />
      <el-button :loading="exporting === 'labels'" @click="download('labels')">{{ $tx('Export order labels') }}</el-button>
      <el-button v-if="isPartnerOrder" :loading="exporting === 'selected-labels'" :disabled="!selectedLabelItems.length" @click="download('labels',true)">{{ $tx('Export selected card labels') }}</el-button>
      <el-button :loading="exporting === 'manifest'" @click="download('manifest')">{{ $tx('Export packing manifest') }}</el-button>
      <el-button v-if="snapshot?.activeSession" type="success" :loading="checking" @click="packingCheck">{{ $tx('Complete packing check') }}</el-button>
      <el-tag v-if="snapshot?.packingCheck?.statusCode === 'passed' && !snapshot.packingCheck.invalidatedAt" type="success">{{ $tx('Packing verified') }}</el-tag>
    </div>
    <p v-if="!orderReturnMode && snapshot?.printJobs?.length" class="print-history">{{ $tx('Recorded exports') }}: {{ snapshot.printJobs.length }} · {{ $tx('Latest') }} {{ $tx(({ labels: 'Labels', manifest: 'Packing manifest' })[snapshot.printJobs[0].exportTypeCode] || snapshot.printJobs[0].exportTypeCode) }} #{{ snapshot.printJobs[0].printSequence }}</p>

    <el-dialog v-model="submissionOpen" :title="$tx('Create Grading Record for This Card')" width="min(720px, calc(100vw - 32px))" append-to-body>
      <el-alert v-if="!orderReturnMode" type="info" :closable="false" show-icon :title="$tx('This record is created inside the locked order and cannot be linked to another order.')" />
      <el-form ref="submissionFormRef" :model="submissionForm" :rules="submissionRules" label-position="top" class="submission-form mt12" :validate-on-rule-change="false">
        <el-row :gutter="12">
          <el-col :xs="24" :sm="12"><el-form-item prop="productType" :label="$tx('Product Type')" required><el-select v-model="submissionForm.productType"><el-option :label="$tx('Graded Card')" value="graded_card" /><el-option :label="$tx('Merch Product')" value="merch_product" /><el-option :label="$tx('Vintage Product')" value="vintage_product" /></el-select></el-form-item></el-col>
          <el-col :xs="24" :sm="12"><el-form-item prop="certId" :label="$tx('Certificate ID')" required><el-input v-model="submissionForm.certId" maxlength="10" inputmode="numeric"><template #append><el-button :loading="generatingCert" @click="generateCertificate">{{ t('common.generate') }}</el-button></template></el-input></el-form-item></el-col>
          <el-col v-if="isGradedSubmission" :xs="24" :sm="12"><el-form-item prop="cardCategory" :label="$tx('Category')"><el-select v-model="submissionForm.cardCategory"><el-option :label="$tx('Trading Card')" value="trading_card" /><el-option :label="$tx('Sports Card')" value="sports_card" /><el-option :label="$tx('Celebrity Card')" value="celebrity_card" /><el-option :label="$tx('Movie Film')" value="movie_film" /></el-select></el-form-item></el-col>
          <el-col v-if="submissionForm.productType === 'merch_product'" :span="24"><el-form-item :label="$tx('Merch Description')"><el-input v-model="submissionForm.merchDescription" type="textarea" :rows="3" maxlength="4000" /></el-form-item></el-col>
          <el-col v-if="submissionForm.productType === 'vintage_product'" :xs="24" :sm="12"><el-form-item prop="vintageClassification" :label="$tx('Vintage Classification')" required><el-select v-model="submissionForm.vintageClassification"><el-option v-for="item in nxr_vintage_classification" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item></el-col>
          <template v-if="isGradedSubmission && submissionForm.cardCategory === 'movie_film'">
            <el-col :xs="24" :sm="12"><el-form-item prop="movieName" :label="$tx('Movie Name')" required><el-input v-model="submissionForm.movieName" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item prop="releaseYear" :label="$tx('Release Year')" required><el-input v-model="submissionForm.releaseYear" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item prop="productionCompany" :label="$tx('Production Company')" required><el-input v-model="submissionForm.productionCompany" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item prop="filmType" :label="$tx('Film Type')" required><el-input v-model="submissionForm.filmType" /></el-form-item></el-col>
          </template>
          <template v-else>
            <el-col :xs="24" :sm="12"><el-form-item prop="cardName" :label="$tx('Card Name')" required><el-input v-model="submissionForm.cardName" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item prop="brandName" :label="$tx('Brand')" required><el-input v-model="submissionForm.brandName" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item prop="setName" :label="$tx('Set Name')" required><el-input v-model="submissionForm.setName" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item prop="cardNumber" :label="$tx('Card Number')" required><el-input v-model="submissionForm.cardNumber" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item :label="$tx('Year')"><el-input v-model="submissionForm.yearLabel" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item prop="languageCode" :label="$tx('Language')" required><el-input v-model="submissionForm.languageCode" maxlength="32" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item :label="t('entries.varietyType')"><el-input v-model="submissionForm.varietyName" /></el-form-item></el-col>
            <el-col v-if="isGradedSubmission && submissionForm.cardCategory === 'sports_card'" :xs="24" :sm="12"><el-form-item prop="sportsType" :label="$tx('Sport')" required><el-input v-model="submissionForm.sportsType" /></el-form-item></el-col>
            <el-col v-if="isGradedSubmission && submissionForm.cardCategory === 'celebrity_card'" :xs="24" :sm="12"><el-form-item prop="groupName" :label="$tx('Group Name')" required><el-input v-model="submissionForm.groupName" /></el-form-item></el-col>
          </template>
          <el-col v-if="isGradedSubmission" :span="24"><el-divider content-position="left">{{ t('entries.gradingDetails') }}</el-divider></el-col>
          <el-col v-for="field in isGradedSubmission ? scoreFields : []" :key="field.key" :xs="12" :sm="6"><el-form-item :prop="field.key" :label="t(field.label)" required><el-input-number v-model="submissionForm[field.key]" :min="1" :max="10" :step="0.1" controls-position="right" /></el-form-item></el-col>
          <el-col v-if="isGradedSubmission" :span="24">
            <div class="grade-preview" aria-live="polite">
              <span>{{ t('entries.finalGradeCalculation') }}</span>
              <strong v-if="gradePreview">{{ gradePreview.finalGradeValue }} · {{ gradePreview.finalGradeLabel }}</strong>
              <span v-else-if="calculatingGrade">{{ t('common.loading') }}</span>
              <span v-else>—</span>
              <template v-if="gradePreviewFailed"><span>{{ t('entries.gradePreviewFailed') }}</span><el-button link type="primary" @click="refreshGradePreview">{{ $tx('Retry') }}</el-button></template>
            </div>
          </el-col>
          <el-col :span="24"><el-form-item :label="$tx('Entry Notes')"><el-input v-model="submissionForm.entryNotes" type="textarea" :rows="2" maxlength="2000" /></el-form-item></el-col>
        </el-row>
      </el-form>
      <template #footer><el-button @click="submissionOpen=false">{{ $tx('Cancel') }}</el-button><el-button type="primary" :loading="creatingSubmission" @click="createSubmission">{{ $tx('Create & Link') }}</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, getCurrentInstance, nextTick, ref, watch, onDeactivated, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { emptySubmission, normalizeProductType, requiredSubmissionFields, scoreFields, validScores, scorePayload, submissionPayload, partnerGradingBlock } from '../gradingForm'
import { saveAs } from 'file-saver'
import NxrCardIdentityPanel from '@/components/NxrCardIdentityPanel.vue'
import { ElMessage } from 'element-plus'
import { copyText } from '@/utils/copyText'
import { approveSubmission, calculateGrade, generateCertId } from '@/api/nxr/entries'
import useUserStore from '@/store/modules/user'
import {
  completeOrderPackingCheck, createOrderItemSubmission, exportOrderLabels, exportOrderManifest,
  getOrderWorkbench, scanOrderWorkbench, startOrderWorkbench
} from '@/api/nxr/orderWorkbench'

const props = defineProps({ orderId: { type: Number, required: true }, orderItems: { type: Array, default: () => [] }, orderReturnMode: { type: Boolean, default: false }, orderStatus: { type: String, default: '' } })
const emit = defineEmits(['changed'])
const { proxy } = getCurrentInstance()
const { t } = useI18n()
const { nxr_vintage_classification } = proxy.useDict('nxr_vintage_classification')
const snapshot = ref(null)
const displayItems = computed(() => props.orderReturnMode ? props.orderItems.map(item => ({ ...item, orderItemId: item.id, certId: item.gradingCertId })) : snapshot.value?.items || [])
const identities = ref({})
const selectedLabelItems = ref([])
const isPartnerOrder = computed(() => Object.values(identities.value).some(card => card.sourceType === 'partner'))
function loadIdentities(value) {
  identities.value = Object.fromEntries(value.items.map(card => [card.orderItemId, card]))
  if (value.items.every(card => card.physicalBarcode) && snapshot.value?.items?.length !== value.items.length) void load()
}
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
onDeactivated(() => { submissionOpen.value = false })
const approvingSubmissionId = ref(null)
const activeWorkbenchItem = ref(null)
const submissionForm = ref(emptySubmission())

const submissionFormRef = ref(null)
const generatingCert = ref(false)
const gradePreview = ref(null)
const gradePreviewFailed = ref(false)
const calculatingGrade = ref(false)
let previewTimer
let previewVersion = 0
const fieldMessages = {
  certId: 'certRequired', productType: 'productTypeRequired', cardCategory: 'categoryRequired',
  vintageClassification: 'vintageRequired', movieName: 'movieNameRequired', releaseYear: 'releaseYearRequired',
  productionCompany: 'productionCompanyRequired', filmType: 'filmTypeRequired', cardName: 'cardNameRequired',
  brandName: 'brandRequired', setName: 'setNameRequired', cardNumber: 'cardNumberRequired',
  languageCode: 'languageRequired', sportsType: 'sportsTypeRequired', groupName: 'groupNameRequired'
}
const submissionRules = computed(() => Object.fromEntries(requiredSubmissionFields(submissionForm.value).map(key => [key, [{
  required: true,
  validator(_rule, value, done) {
    const score = scoreFields.find(field => field.key === key)
    let message = ''
    if (score && (value === null || value === '' || !Number.isFinite(Number(value)) || Number(value) < 1 || Number(value) > 10)) message = t('entries.scoreRange', { label: t(score.label) })
    else if (!score && !String(value ?? '').trim()) message = t(`entries.${fieldMessages[key]}`)
    else if (key === 'certId' && !/^[1-9]\d{9}$/.test(String(value).trim())) message = t('entries.certFormat')
    done(message ? new Error(message) : undefined)
  }, trigger: ['blur', 'change']
}]])))

function invalidateGradePreview() {
  clearTimeout(previewTimer)
  previewVersion++
  gradePreview.value = null
  gradePreviewFailed.value = false
  calculatingGrade.value = false
}
async function refreshGradePreview() {
  invalidateGradePreview()
  if (!submissionOpen.value || !isGradedSubmission.value || !validScores(submissionForm.value)) return
  const version = previewVersion
  calculatingGrade.value = true
  try {
    // The server uses the same rounding as Python; do not duplicate it in JavaScript.
    const result = await calculateGrade(scorePayload(submissionForm.value))
    if (version === previewVersion) gradePreview.value = result.data
  } catch {
    if (version === previewVersion) gradePreviewFailed.value = true
  } finally {
    if (version === previewVersion) calculatingGrade.value = false
  }
}
async function generateCertificate() {
  generatingCert.value = true
  const target = submissionForm.value
  try {
    const result = await generateCertId()
    if (submissionOpen.value && submissionForm.value === target) {
      target.certId = result.data.certId
      submissionFormRef.value?.clearValidate('certId')
    }
  } finally { generatingCert.value = false }
}
function openSubmission(item) {
  if (props.orderReturnMode && partnerGradingBlock(props.orderStatus, item.statusCode)) return
  activeWorkbenchItem.value = item
  const source = props.orderItems.find(row => row.id === item.orderItemId) || {}
  submissionForm.value = { ...emptySubmission(), productType: normalizeProductType(source.productType), cardName: source.cardName || item.cardName || '', brandName: source.brandName || '', yearLabel: source.year || '', setName: source.setName || '', cardNumber: source.cardNumber || '', languageCode: source.languageCode || 'EN', cardCategory: source.category || source.cardCategory || 'trading_card' }
  submissionOpen.value = true
  nextTick(() => submissionFormRef.value?.clearValidate())
}
async function createSubmission() {
  if (!activeWorkbenchItem.value || creatingSubmission.value) return
  if (!await submissionFormRef.value?.validate().catch(() => false)) return
  creatingSubmission.value = true
  try {
    await createOrderItemSubmission(props.orderId, activeWorkbenchItem.value.orderItemId, submissionPayload(submissionForm.value))
    submissionOpen.value = false
    if (props.orderReturnMode) emit('changed'); else await load()
  } finally { creatingSubmission.value = false }
}
async function approve(item) {
  approvingSubmissionId.value = item.gradingSubmissionId
  try { await approveSubmission(item.gradingSubmissionId); if (props.orderReturnMode) emit('changed'); else await load() }
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
  if (await copyText(value)) ElMessage.success(proxy.$tx('Copied'))
  else ElMessage.warning(proxy.$tx('Copy failed. Select the text and copy it manually.'))
}
async function packingCheck() {
  if (!snapshot.value?.activeSession) return
  checking.value = true
  try { snapshot.value = (await completeOrderPackingCheck(props.orderId, { sessionId: snapshot.value.activeSession.id })).data } finally { checking.value = false }
}
async function download(type, selectedOnly = false) {
  if (selectedOnly && !selectedLabelItems.value.length) return
  exporting.value = selectedOnly ? 'selected-labels' : type
  try {
    const call = type === 'labels' ? exportOrderLabels : exportOrderManifest
    const blob = await call(props.orderId, { reprintReason: reprintReason.value, ...(selectedOnly ? {orderItemIds:selectedLabelItems.value.map(item=>item.orderItemId)} : {}) })
    saveAs(new Blob([blob], { type: 'text/csv;charset=UTF-8' }), `nxr-order-${props.orderId}-${type}.csv`)
    reprintReason.value = ''
    await load()
  } finally { exporting.value = '' }
}

watch(() => submissionForm.value.productType, type => {
  if (type !== 'graded_card') {
    submissionForm.value.cardCategory = 'trading_card'
    submissionForm.value.sportsType = ''
    submissionForm.value.groupName = ''
    for (const { key } of scoreFields) submissionForm.value[key] = null
  } else {
    for (const { key } of scoreFields) if (submissionForm.value[key] == null) submissionForm.value[key] = 1
  }
  if (type !== 'vintage_product') submissionForm.value.vintageClassification = ''
  if (type !== 'merch_product') submissionForm.value.merchDescription = ''
  nextTick(() => submissionFormRef.value?.clearValidate())
})
watch(() => [submissionOpen.value, submissionForm.value.productType, ...scoreFields.map(({ key }) => submissionForm.value[key])], () => {
  invalidateGradePreview()
  if (submissionOpen.value && isGradedSubmission.value && validScores(submissionForm.value)) {
    calculatingGrade.value = true
    previewTimer = setTimeout(refreshGradePreview, 300)
  }
}, { flush: 'sync' })
onBeforeUnmount(invalidateGradePreview)

watch(() => [props.orderId, props.orderReturnMode], () => { identities.value = {}; if (!props.orderReturnMode) void load() }, { immediate: true })
</script>

<style scoped>
.workbench-panel{display:grid;gap:12px}.panel-heading{display:flex;justify-content:space-between;align-items:center;gap:16px}.panel-heading p,.print-history{margin:4px 0 0;color:var(--nxr-text-faint)}.scan-row{margin-top:4px}.grading-block{display:block;color:var(--nxr-text-muted);line-height:1.4}.panel-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.panel-actions .el-input{width:min(300px,100%)}
@media(max-width:760px){.panel-heading{align-items:stretch;flex-direction:column}.panel-actions>*{width:100%!important}}
.submission-form :deep(.el-input-number),.submission-form :deep(.el-select){width:100%}
.grade-preview{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-bottom:18px;padding:12px;border:1px solid var(--el-border-color);border-radius:6px}
</style>
