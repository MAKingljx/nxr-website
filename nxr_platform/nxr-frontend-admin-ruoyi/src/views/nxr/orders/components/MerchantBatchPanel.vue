<template>
  <el-card shadow="never" class="mb12">
    <template #header><div class="batch-heading"><strong>子代理寄件批次</strong><el-button icon="Refresh" circle :loading="loading" @click="load()" /></div></template>
    <el-form inline @submit.prevent="load(true)"><el-form-item label="查找批次"><el-input v-model="query" clearable placeholder="批次号或批次名称" /></el-form-item><el-form-item><el-button native-type="submit" :loading="loading">搜索</el-button></el-form-item></el-form>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />
    <el-table v-loading="loading" :data="rows" size="small">
      <el-table-column :label="$tx('Batch')" prop="batchNo" min-width="170" />
      <el-table-column :label="$tx('Name')" prop="batchName" min-width="160" />
      <el-table-column :label="$tx('Orders')" width="100"><template #default="scope">{{ scope.row.acceptedRows }}/{{ scope.row.totalRows }}</template></el-table-column>
      <el-table-column :label="$tx('Status')" width="140"><template #default="scope">{{ statusLabel(scope.row.statusCode) }}</template></el-table-column>
      <el-table-column :label="$tx('Created At')" width="170"><template #default="scope">{{ parseTime(scope.row.createdAt) }}</template></el-table-column>
      <el-table-column :label="$tx('Actions')" width="90"><template #default="scope"><el-button link type="primary" @click="open(scope.row.id)">{{ $tx('View') }}</el-button></template></el-table-column>
    </el-table>
    <pagination v-show="total>0" :total="total" v-model:page="page" v-model:limit="pageSize" @pagination="load()" />
    <el-dialog v-model="openDialog" title="子代理寄件批次" width="min(900px, calc(100vw - 32px))" append-to-body>
      <template v-if="detail">
        <el-descriptions :column="3" border><el-descriptions-item :label="$tx('Batch')">{{ detail.batchNo }}</el-descriptions-item><el-descriptions-item :label="$tx('Name')">{{ detail.batchName }}</el-descriptions-item><el-descriptions-item :label="$tx('Status')">{{ statusLabel(detail.statusCode) }}</el-descriptions-item></el-descriptions>
        <el-divider content-position="left">{{ $tx('Independent customer orders') }}</el-divider>
        <el-table :data="detail.orders" size="small" border><el-table-column label="#" prop="rowNo" width="54" /><el-table-column :label="$tx('Client reference')" prop="clientReference" min-width="150" /><el-table-column :label="$tx('Customer label')" prop="clientDisplayName" min-width="140" /><el-table-column :label="$tx('Order No.')" min-width="150"><template #default="scope"><el-button link type="primary" @click="openOrder(scope.row.orderId)">{{scope.row.orderNo}}</el-button></template></el-table-column><el-table-column :label="$tx('Cards')" prop="totalCardCount" width="70" /><el-table-column :label="$tx('Status')" width="150"><template #default="scope">{{ statusLabel(scope.row.statusCode === 'admission_review' ? scope.row.admissionStatus || scope.row.statusCode : scope.row.statusCode) }}</template></el-table-column><el-table-column :label="$tx('Private link')" width="100"><template #default="scope">{{ scope.row.trackingTokenHint ? `${scope.row.trackingTokenHint}…` : $tx('Revoked') }}</template></el-table-column></el-table>
        <el-divider content-position="left">{{ $tx('Master parcel logistics') }}</el-divider>
        <el-table v-if="detail.shipments.length" :data="detail.shipments" size="small" border><el-table-column :label="$tx('Direction')" width="100"><template #default="scope">{{ $tx(scope.row.directionCode === 'inbound' ? 'Inbound' : 'Outbound') }}</template></el-table-column><el-table-column :label="$tx('Carrier')" prop="carrierName" /><el-table-column :label="$tx('Tracking No.')" prop="trackingNumber" min-width="180" /><el-table-column :label="$tx('Status')" width="120"><template #default="scope">{{ statusLabel(scope.row.statusCode) }}</template></el-table-column><el-table-column :label="$tx('Actions')" width="120"><template #default="scope"><el-button v-if="!scope.row.deliveredAt" link type="success" @click="delivered(scope.row)">{{ $tx('Mark Delivered') }}</el-button></template></el-table-column></el-table>
        <el-form v-if="detail.orders.length && detail.orders.every(order => order.statusCode === 'completed') && !['return_shipped', 'delivered', 'cancelled'].includes(detail.statusCode)" v-hasPermi="['nxr:order:manage','nxr:order:shipping','nxr:order:batch']" :inline="true" :model="shipment" class="outbound-form"><el-form-item :label="$tx('Carrier')"><el-input v-model="shipment.carrierName" /></el-form-item><el-form-item :label="$tx('Tracking No.')"><el-input v-model="shipment.trackingNumber" /></el-form-item><el-form-item><el-button type="primary" :loading="shipping" @click="shipOutbound">{{ $tx('Create outbound master parcel') }}</el-button></el-form-item></el-form>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, getCurrentInstance, watch } from 'vue'
import { useRoute } from 'vue-router'
import auth from '@/plugins/auth'
import { createMerchantBatchOutbound, getMerchantBatch, listMerchantBatches, markMerchantBatchShipmentDelivered } from '@/api/nxr/orderWorkbench'
const { proxy } = getCurrentInstance()
const emit = defineEmits(['open-order'])
const route = useRoute()
const page = ref(1), pageSize = ref(20), total = ref(0), query = ref(''), loadError = ref('')
const canView = auth.hasPermiOr(['nxr:order:manage','nxr:order:warehouse','nxr:order:shipping','nxr:order:batch'])
let detailGeneration = 0
function openOrder(id) { openDialog.value = false; emit('open-order', id) }
const statusLabels = {
  open: 'Open', admission_review: 'Application Review', pending_review: 'Application under review',
  needs_information: 'More information needed', rejected: 'Rejected', terms_confirmation: 'Awaiting Terms Confirmation',
  awaiting_payment: 'Awaiting Payment', payment_review: 'Payment Review', payment_expired: 'Payment Deadline Expired',
  payment_exception: '付款异常待核查', awaiting_inbound: 'Awaiting Cards', inbound_shipped: 'Shipped to NXR',
  intake_exception: 'Intake Exception', received: 'Cards Received', grading: 'Grading', review: 'Review',
  quality_check: 'Quality Check', quality_hold: 'QC Rework', completed: 'Ready to Return',
  return_shipped: 'Return Shipped', shipped: 'In Transit', in_transit: 'In Transit', delivered: 'Delivered', cancelled: 'Cancelled'
}
function statusLabel(value) { return proxy.$tx(statusLabels[value] || value || 'Pending') }
const rows = ref([]); const detail = ref(null); const loading = ref(false); const openDialog = ref(false); const shipping = ref(false)
const shipment = ref({ carrierName: '', trackingNumber: '', note: '' })
async function load(reset=false){if(!canView)return;if(reset===true)page.value=1;loading.value=true;loadError.value='';try{const result=(await listMerchantBatches({page:page.value,pageSize:pageSize.value,query:query.value})).data;rows.value=result.items;total.value=result.total}catch(e){loadError.value=e.message||'批次加载失败。'}finally{loading.value=false}}
async function open(id){if(!canView)return;const current=++detailGeneration;detail.value=null;loadError.value='';try{const value=(await getMerchantBatch(id)).data;if(current===detailGeneration){detail.value=value;openDialog.value=true}}catch(e){if(current===detailGeneration)loadError.value=e.message||'批次详情加载失败。'}}
async function shipOutbound(){ if(!detail.value||!shipment.value.carrierName.trim()||!shipment.value.trackingNumber.trim())return; shipping.value=true; try{ detail.value=(await createMerchantBatchOutbound(detail.value.id,shipment.value)).data; shipment.value={carrierName:'',trackingNumber:'',note:''}; await load() }finally{ shipping.value=false } }
async function delivered(row){ if(!detail.value)return; detail.value=(await markMerchantBatchShipmentDelivered(detail.value.id,row.id)).data; await load() }
watch(()=>route.query.batchId,raw=>{const id=Number(raw);if(Number.isSafeInteger(id)&&id>0)void open(id)},{immediate:true})
load()
</script>

<style scoped>.batch-heading{display:flex;justify-content:space-between;align-items:center}.outbound-form{margin-top:14px}</style>
