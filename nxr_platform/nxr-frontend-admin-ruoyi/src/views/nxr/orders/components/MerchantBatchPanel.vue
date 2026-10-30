<template>
  <el-card shadow="never" class="mb12">
    <template #header><div class="batch-heading"><strong>{{ $tx('Partner shipment batches') }}</strong><el-button icon="Refresh" circle :loading="loading" @click="load()" /></div></template>
    <el-form inline @submit.prevent="load(true)"><el-form-item :label="$tx('Find a batch')"><el-input v-model="query" clearable :placeholder="$tx('Batch number or name')" /></el-form-item><el-form-item><el-button native-type="submit" :loading="loading">{{ $tx('Search') }}</el-button></el-form-item></el-form>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />
    <el-table v-loading="loading" :data="rows" size="small">
      <el-table-column :label="$tx('Submit ID')" prop="batchNo" min-width="170" />
      <el-table-column :label="$tx('Submit name')" prop="batchName" min-width="160" />
      <el-table-column :label="$tx('Orders')" width="100"><template #default="scope">{{ scope.row.acceptedRows }}/{{ scope.row.totalRows }}</template></el-table-column>
      <el-table-column :label="$tx('Status')" width="140"><template #default="scope">{{ batchStatusLabel(scope.row.statusCode) }}</template></el-table-column>
      <el-table-column :label="$tx('Created At')" width="170"><template #default="scope">{{ parseTime(scope.row.createdAt) }}</template></el-table-column>
      <el-table-column :label="$tx('Actions')" width="90"><template #default="scope"><el-button link type="primary" @click="open(scope.row.id)">{{ $tx('View') }}</el-button></template></el-table-column>
    </el-table>
    <pagination v-show="total>0" :total="total" v-model:page="page" v-model:limit="pageSize" @pagination="load()" />
    <el-drawer v-model="openDialog" :title="$tx('Partner shipment batches')" size="min(1050px, 100vw)" append-to-body>
      <template v-if="detail">
        <el-descriptions :column="3" border><el-descriptions-item :label="$tx('Submit ID')">{{ detail.batchNo }}</el-descriptions-item><el-descriptions-item :label="$tx('Submit name')">{{ detail.batchName }}</el-descriptions-item><el-descriptions-item :label="$tx('Status')">{{ batchStatusLabel(detail.statusCode) }}</el-descriptions-item></el-descriptions>
        <partner-submit-admission-panel v-if="canReview" :key="`${detail.id}:${reviewGeneration}`" :batch-id="detail.id" :orders="detail.orders" @changed="refreshReview" @open-order="openOrder" />
        <el-divider content-position="left">{{ $tx('Independent customer orders') }}</el-divider>
        <el-table :data="detail.orders" size="small" border style="width: 100%"><el-table-column :label="$tx('No.')" prop="rowNo" width="70" /><el-table-column :label="$tx('Order No.')" min-width="200"><template #default="scope"><el-button link type="primary" @click="openOrder(scope.row.orderId)">{{scope.row.orderNo}}</el-button></template></el-table-column><el-table-column :label="$tx('Customer label')" prop="clientDisplayName" min-width="160" /><el-table-column :label="$tx('Cards')" prop="totalCardCount" width="80" /><el-table-column :label="$tx('Status')" min-width="170"><template #default="scope">{{ statusLabel(scope.row.statusCode === 'admission_review' ? scope.row.admissionStatus || scope.row.statusCode : scope.row.statusCode) }}</template></el-table-column><el-table-column :label="$tx('Private link')" min-width="300"><template #default="scope"><div v-if="trackingLinks[scope.row.orderNo]" class="tracking-link-cell"><el-input :model-value="trackingLinks[scope.row.orderNo]" readonly size="small" @focus="$event.target.select()" /><el-button link type="primary" @click="copyTrackingLink(scope.row)">{{ $tx('Copy link') }}</el-button><el-button link type="warning" @click="rotateTrackingLink(scope.row)">{{ $tx('Regenerate') }}</el-button></div><div v-else class="tracking-link-cell"><span class="tracking-hint" :title="scope.row.trackingTokenHint ? `${scope.row.trackingTokenHint}…` : ''">{{ scope.row.trackingTokenHint ? `${scope.row.trackingTokenHint}…` : $tx('Not created') }}</span><el-button link type="primary" :loading="trackingBusy === scope.row.orderNo" :disabled="Boolean(trackingBusy)" @click="scope.row.trackingTokenHint ? loadTrackingLink(scope.row) : rotateTrackingLink(scope.row, false)">{{ scope.row.trackingTokenHint ? $tx('Show link') : $tx('Create private link') }}</el-button></div></template></el-table-column></el-table>
        <div class="batch-return-action"><el-button v-hasPermi="['nxr:order:manage','nxr:order:shipping']" type="primary" @click="openDialog=false; emit('open-returns')">{{ $tx('Select cards for return') }}</el-button></div><el-divider content-position="left">{{ $tx('Master parcel logistics') }}</el-divider>
        <el-table v-if="detail.shipments.length" :data="detail.shipments" size="small" border style="width: 100%"><el-table-column :label="$tx('Direction')" width="100"><template #default="scope">{{ $tx(scope.row.directionCode === 'inbound' ? 'Inbound' : 'Outbound') }}</template></el-table-column><el-table-column :label="$tx('Carrier')" prop="carrierName" /><el-table-column :label="$tx('Tracking No.')" prop="trackingNumber" min-width="180" /><el-table-column :label="$tx('Status')" width="120"><template #default="scope">{{ statusLabel(scope.row.statusCode) }}</template></el-table-column><el-table-column :label="$tx('Actions')" width="120"><template #default="scope"><el-button v-if="!scope.row.deliveredAt" v-hasPermi="['nxr:order:manage','nxr:order:shipping','nxr:order:batch']" link type="success" @click="delivered(scope.row)">{{ $tx('Mark Delivered') }}</el-button></template></el-table-column></el-table>
        <el-form v-if="detail.orders.length && !detail.orders.some(order => order.intakeNo) && detail.orders.every(order => order.statusCode === 'completed') && !['return_shipped', 'delivered', 'cancelled'].includes(detail.statusCode)" v-hasPermi="['nxr:order:manage','nxr:order:shipping','nxr:order:batch']" :inline="true" :model="shipment" class="outbound-form"><el-form-item :label="$tx('Carrier')"><el-input v-model="shipment.carrierName" /></el-form-item><el-form-item :label="$tx('Tracking No.')"><el-input v-model="shipment.trackingNumber" /></el-form-item><el-form-item><el-button type="primary" :loading="shipping" @click="shipOutbound">{{ $tx('Create outbound master parcel') }}</el-button></el-form-item></el-form>
      </template>
    </el-drawer>
  </el-card>
</template>

<script setup>
import { tx } from '@/i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { copyText } from '@/utils/copyText'
import { ref, getCurrentInstance, watch, onDeactivated } from 'vue'
import { useRoute } from 'vue-router'
import auth from '@/plugins/auth'
import { createMerchantBatchOutbound, getMerchantBatch, getMerchantBatchTrackingToken, listMerchantBatches, markMerchantBatchShipmentDelivered, rotateMerchantBatchTrackingToken } from '@/api/nxr/orderWorkbench'
import PartnerSubmitAdmissionPanel from './PartnerSubmitAdmissionPanel.vue'
const { proxy } = getCurrentInstance()
const emit = defineEmits(['open-order','open-returns'])
const route = useRoute()
const page = ref(1), pageSize = ref(20), total = ref(0), query = ref(''), loadError = ref('')
const canView = auth.hasPermiOr(['nxr:order:manage','nxr:order:warehouse','nxr:order:shipping','nxr:order:batch','nxr:order:admission'])
const canReview = auth.hasPermiOr(['nxr:order:manage','nxr:order:admission'])
const reviewGeneration = ref(0)
const trackingLinks = ref({})
const trackingBusy = ref('')
let detailGeneration = 0
function openOrder(id) { openDialog.value = false; emit('open-order', id) }
const statusLabels = {
  open: 'Open', admission_review: 'Application Review', pending_review: 'Application under review',
  needs_information: 'More information needed', rejected: 'Rejected', terms_confirmation: 'Awaiting Terms Confirmation',
  awaiting_payment: 'Awaiting Payment', payment_review: 'Payment Review', payment_expired: 'Payment Deadline Expired',
  payment_exception: 'Payment exception under review', awaiting_inbound: 'Awaiting Cards', inbound_shipped: 'Shipped to NXR',
  intake_exception: 'Intake Exception', received: 'Cards Received', grading: 'Grading', review: 'Review',
  quality_check: 'Quality Check', quality_hold: 'QC Rework', completed: 'Ready to Return',
  return_shipped: 'Return Shipped', shipped: 'In Transit', in_transit: 'In Transit', delivered: 'Delivered', cancelled: 'Cancelled'
}
defineExpose({ open })
function statusLabel(value) { return proxy.$tx(statusLabels[value] || value || 'Pending') }
function batchStatusLabel(value) { return value === 'delivered' ? tx('Sub-agent received batch') : statusLabel(value) }
const rows = ref([]); const detail = ref(null); const loading = ref(false); const openDialog = ref(false); const shipping = ref(false)
const shipment = ref({ carrierName: '', trackingNumber: '', note: '' })
onDeactivated(() => { detailGeneration++; openDialog.value = false })
async function load(reset=false){if(!canView)return;if(reset===true)page.value=1;loading.value=true;loadError.value='';try{const result=(await listMerchantBatches({page:page.value,pageSize:pageSize.value,query:query.value})).data;rows.value=result.items;total.value=result.total}catch(e){loadError.value=e.message||tx('Unable to load batches.')}finally{loading.value=false}}
async function open(id){if(!canView)return;const current=++detailGeneration;detail.value=null;loadError.value='';try{const value=(await getMerchantBatch(id)).data;if(current===detailGeneration){detail.value=value;openDialog.value=true;void hydrateTrackingLinks()}}catch(e){if(current===detailGeneration)loadError.value=e.message||tx('Unable to load batch details.')}}
async function refreshReview(){const id=detail.value?.id;if(!id)return;const current=++detailGeneration;try{const value=(await getMerchantBatch(id)).data;if(current===detailGeneration){detail.value=value;reviewGeneration.value++}await load()}catch(e){if(current===detailGeneration)loadError.value=e.message||tx('Unable to load batch details.')}}
async function rotateTrackingLink(row, confirm = true) {
  if (confirm && row.trackingTokenHint) {
    try {
      await ElMessageBox.confirm(
        tx('Generating a new link invalidates the previous link. Continue?'),
        tx('Private link'),
        { type: 'warning' }
      )
    } catch { return }
  }
  trackingBusy.value = row.orderNo
  try {
    const res = await rotateMerchantBatchTrackingToken(detail.value.id, row.orderNo)
    trackingLinks.value = { ...trackingLinks.value, [row.orderNo]: res.data.trackingUrl }
    row.trackingTokenHint = res.data.trackingToken ? res.data.trackingToken.slice(0, 10) : row.trackingTokenHint
    ElMessage.success(tx('Private link created. Share it only with the customer.'))
  } catch (e) {
    ElMessage.error(e.message || tx('Unable to create private link'))
  } finally {
    trackingBusy.value = ''
  }
}
async function loadTrackingLink(row) {
  trackingBusy.value = row.orderNo
  try {
    const res = await getMerchantBatchTrackingToken(detail.value.id, row.orderNo)
    const view = res.data || {}
    if (view.viewable && view.trackingUrl) {
      trackingLinks.value = { ...trackingLinks.value, [row.orderNo]: view.trackingUrl }
      return
    }
    if (view.legacy) {
      try {
        await ElMessageBox.confirm(
          tx('This link was created by an older version. Regenerate it once to make it viewable. The old link will stop working.'),
          tx('Private link'),
          { type: 'warning' }
        )
      } catch { return }
    }
    await rotateTrackingLink(row, false)
  } catch (e) {
    ElMessage.error(e.message || tx('Unable to load private link'))
  } finally {
    trackingBusy.value = ''
  }
}
async function hydrateTrackingLinks() {
  const batch = detail.value
  if (!batch?.id) return
  const candidates = (batch.orders || []).filter(order => order.trackingTokenHint).slice(0, 50)
  if (!candidates.length) return
  const results = await Promise.allSettled(candidates.map(order => getMerchantBatchTrackingToken(batch.id, order.orderNo)))
  const next = { ...trackingLinks.value }
  results.forEach((result, index) => {
    const view = result.status === 'fulfilled' ? result.value?.data : null
    if (view?.viewable && view.trackingUrl) next[candidates[index].orderNo] = view.trackingUrl
  })
  trackingLinks.value = next
}
async function copyTrackingLink(row) {
  const ok = await copyText(trackingLinks.value[row.orderNo] || '')
  ElMessage[ok ? 'success' : 'error'](tx(ok ? 'Link copied' : 'Copy failed'))
}
async function shipOutbound(){ if(!detail.value||!shipment.value.carrierName.trim()||!shipment.value.trackingNumber.trim())return; shipping.value=true; try{ detail.value=(await createMerchantBatchOutbound(detail.value.id,shipment.value)).data; shipment.value={carrierName:'',trackingNumber:'',note:''}; await load() }finally{ shipping.value=false } }
async function delivered(row){ if(!detail.value)return; detail.value=(await markMerchantBatchShipmentDelivered(detail.value.id,row.id)).data; await load() }
watch(()=>route.query.batchId,raw=>{const id=Number(raw);if(Number.isSafeInteger(id)&&id>0)void open(id)},{immediate:true})
load()
</script>

<style scoped>.batch-heading{display:flex;justify-content:space-between;align-items:center}.outbound-form{margin-top:14px}.batch-return-action{margin-top:18px}</style>
