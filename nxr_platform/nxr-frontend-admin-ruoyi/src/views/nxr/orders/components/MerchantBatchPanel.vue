<template>
  <el-card shadow="never" class="mb12">
    <template #header><div class="batch-heading"><strong>{{ $tx('Merchant master parcels') }}</strong><el-button icon="Refresh" circle :loading="loading" @click="load" /></div></template>
    <el-table v-loading="loading" :data="rows" size="small">
      <el-table-column :label="$tx('Batch')" prop="batchNo" min-width="170" />
      <el-table-column :label="$tx('Name')" prop="batchName" min-width="160" />
      <el-table-column :label="$tx('Orders')" width="100"><template #default="scope">{{ scope.row.acceptedRows }}/{{ scope.row.totalRows }}</template></el-table-column>
      <el-table-column :label="$tx('Status')" width="140"><template #default="scope">{{ statusLabel(scope.row.statusCode) }}</template></el-table-column>
      <el-table-column :label="$tx('Created At')" width="170"><template #default="scope">{{ parseTime(scope.row.createdAt) }}</template></el-table-column>
      <el-table-column :label="$tx('Actions')" width="90"><template #default="scope"><el-button link type="primary" @click="open(scope.row.id)">{{ $tx('View') }}</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="openDialog" :title="$tx('Merchant batch')" width="min(900px, calc(100vw - 32px))" append-to-body>
      <template v-if="detail">
        <el-descriptions :column="3" border><el-descriptions-item :label="$tx('Batch')">{{ detail.batchNo }}</el-descriptions-item><el-descriptions-item :label="$tx('Name')">{{ detail.batchName }}</el-descriptions-item><el-descriptions-item :label="$tx('Status')">{{ statusLabel(detail.statusCode) }}</el-descriptions-item></el-descriptions>
        <el-divider content-position="left">{{ $tx('Independent customer orders') }}</el-divider>
        <el-table :data="detail.orders" size="small" border><el-table-column label="#" prop="rowNo" width="54" /><el-table-column :label="$tx('Client reference')" prop="clientReference" min-width="150" /><el-table-column :label="$tx('Customer label')" prop="clientDisplayName" min-width="140" /><el-table-column :label="$tx('Order No.')" prop="orderNo" min-width="150" /><el-table-column :label="$tx('Cards')" prop="totalCardCount" width="70" /><el-table-column :label="$tx('Status')" width="150"><template #default="scope">{{ statusLabel(scope.row.statusCode === 'admission_review' ? scope.row.admissionStatus || scope.row.statusCode : scope.row.statusCode) }}</template></el-table-column><el-table-column :label="$tx('Private link')" width="100"><template #default="scope">{{ scope.row.trackingTokenHint ? `${scope.row.trackingTokenHint}…` : $tx('Revoked') }}</template></el-table-column></el-table>
        <el-divider content-position="left">{{ $tx('Master parcel logistics') }}</el-divider>
        <el-table v-if="detail.shipments.length" :data="detail.shipments" size="small" border><el-table-column :label="$tx('Direction')" width="100"><template #default="scope">{{ $tx(scope.row.directionCode === 'inbound' ? 'Inbound' : 'Outbound') }}</template></el-table-column><el-table-column :label="$tx('Carrier')" prop="carrierName" /><el-table-column :label="$tx('Tracking No.')" prop="trackingNumber" min-width="180" /><el-table-column :label="$tx('Status')" width="120"><template #default="scope">{{ statusLabel(scope.row.statusCode) }}</template></el-table-column><el-table-column :label="$tx('Actions')" width="120"><template #default="scope"><el-button v-if="!scope.row.deliveredAt" link type="success" @click="delivered(scope.row)">{{ $tx('Mark Delivered') }}</el-button></template></el-table-column></el-table>
        <el-form v-if="!['return_shipped', 'delivered', 'cancelled'].includes(detail.statusCode)" :inline="true" :model="shipment" class="outbound-form"><el-form-item :label="$tx('Carrier')"><el-input v-model="shipment.carrierName" /></el-form-item><el-form-item :label="$tx('Tracking No.')"><el-input v-model="shipment.trackingNumber" /></el-form-item><el-form-item><el-button type="primary" :loading="shipping" @click="shipOutbound">{{ $tx('Create outbound master parcel') }}</el-button></el-form-item></el-form>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, getCurrentInstance } from 'vue'
import { createMerchantBatchOutbound, getMerchantBatch, listMerchantBatches, markMerchantBatchShipmentDelivered } from '@/api/nxr/orderWorkbench'
const { proxy } = getCurrentInstance()
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
async function load(){ loading.value=true; try{ rows.value=(await listMerchantBatches({page:1,pageSize:20})).data.items }finally{ loading.value=false } }
async function open(id){ detail.value=(await getMerchantBatch(id)).data; openDialog.value=true }
async function shipOutbound(){ if(!detail.value||!shipment.value.carrierName.trim()||!shipment.value.trackingNumber.trim())return; shipping.value=true; try{ detail.value=(await createMerchantBatchOutbound(detail.value.id,shipment.value)).data; shipment.value={carrierName:'',trackingNumber:'',note:''}; await load() }finally{ shipping.value=false } }
async function delivered(row){ if(!detail.value)return; detail.value=(await markMerchantBatchShipmentDelivered(detail.value.id,row.id)).data; await load() }
load()
</script>

<style scoped>.batch-heading{display:flex;justify-content:space-between;align-items:center}.outbound-form{margin-top:14px}</style>
