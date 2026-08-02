<template>
  <div class="app-container">
    <el-form ref="queryRef" :model="queryParams" :inline="true" @submit.prevent>
      <el-form-item label="订单状态" prop="status">
        <el-select v-model="queryParams.status" clearable placeholder="全部状态" style="width: 180px">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="关键词" prop="query">
        <el-input v-model="queryParams.query" clearable placeholder="订单号 / 客户邮箱 / 客户名称" style="width: 260px" @keyup.enter="loadOrders(true)" />
      </el-form-item>
      <el-form-item><el-button type="primary" icon="Search" @click="loadOrders(true)">搜索</el-button><el-button icon="Refresh" @click="resetQuery">重置</el-button></el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows">
      <el-table-column label="订单号" prop="orderNo" min-width="150" />
      <el-table-column label="客户" min-width="190" show-overflow-tooltip><template #default="scope"><div>{{ scope.row.customer.displayName }}</div><small>{{ scope.row.customer.email }}</small></template></el-table-column>
      <el-table-column label="卡数" prop="totalCardCount" width="80" align="center" />
      <el-table-column label="服务" prop="serviceLevelCode" width="105" align="center" />
      <el-table-column label="金额" width="110" align="right"><template #default="scope">{{ scope.row.currencyCode }} {{ Number(scope.row.totalAmount).toFixed(2) }}</template></el-table-column>
      <el-table-column label="状态" width="150" align="center"><template #default="scope"><el-tag :type="statusType(scope.row.statusCode)">{{ labelStatus(scope.row.statusCode) }}</el-tag></template></el-table-column>
      <el-table-column label="创建时间" width="170" align="center"><template #default="scope">{{ parseTime(scope.row.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" width="100" align="center"><template #default="scope"><el-button link type="primary" icon="View" @click="openDetail(scope.row.id)">详情</el-button></template></el-table-column>
    </el-table>

    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.page" v-model:limit="queryParams.pageSize" @pagination="loadOrders" />

    <el-dialog v-model="detailOpen" title="送评订单详情" width="1080px" append-to-body>
      <template v-if="detail">
        <el-descriptions :column="3" border class="mb12">
          <el-descriptions-item label="订单号">{{ detail.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="订单状态"><el-tag :type="statusType(detail.statusCode)">{{ labelStatus(detail.statusCode) }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="服务 / 卡数">{{ detail.serviceLevelCode }} / {{ detail.totalCardCount }}</el-descriptions-item>
          <el-descriptions-item label="客户" :span="2">{{ detail.customer.displayName }} · {{ detail.customer.email }}</el-descriptions-item>
          <el-descriptions-item label="联系电话">{{ detail.contactPhone }}</el-descriptions-item>
          <el-descriptions-item label="回寄地址" :span="3">{{ [detail.returnAddressLine1, detail.returnAddressLine2, detail.returnCity, detail.returnRegion, detail.returnPostalCode, detail.returnCountry].filter(Boolean).join(', ') }}</el-descriptions-item>
          <el-descriptions-item label="金额">{{ detail.currencyCode }} {{ Number(detail.totalAmount).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="服务费">{{ detail.currencyCode }} {{ Number(detail.serviceFee).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="回寄运费">{{ detail.currencyCode }} {{ Number(detail.returnShippingFee).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="客户备注" :span="3">{{ detail.customerNote || '-' }}</el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">订单卡牌与评分关联</el-divider>
        <el-table :data="detail.items" size="small" border>
          <el-table-column label="#" prop="itemNo" width="55" align="center" />
          <el-table-column label="卡名" prop="cardName" min-width="180" />
          <el-table-column label="信息" min-width="180"><template #default="scope">{{ [scope.row.brandName, scope.row.setName, scope.row.cardNumber, scope.row.languageCode].filter(Boolean).join(' · ') || '-' }}</template></el-table-column>
          <el-table-column label="条目状态" width="130"><template #default="scope"><el-tag>{{ labelStatus(scope.row.statusCode) }}</el-tag></template></el-table-column>
          <el-table-column label="评分条目" min-width="220"><template #default="scope"><span v-if="scope.row.gradingSubmissionId">#{{ scope.row.gradingSubmissionId }} · {{ scope.row.gradingCertId }} · {{ scope.row.gradingStatusCode }}</span><div v-else class="link-submission"><el-input-number v-model="submissionLinks[scope.row.id]" :min="1" :controls="false" placeholder="评分条目 ID" /><el-button v-hasPermi="['nxr:order:manage']" link type="primary" :loading="linkingItemId === scope.row.id" @click="linkSubmission(scope.row)">关联</el-button></div></template></el-table-column>
        </el-table>

        <el-divider content-position="left">收款</el-divider>
        <el-table :data="detail.payments" size="small" border>
          <el-table-column label="类型" width="120"><template #default="scope">{{ scope.row.paymentTypeCode }}</template></el-table-column>
          <el-table-column label="金额" width="130"><template #default="scope">{{ scope.row.currencyCode }} {{ Number(scope.row.amount).toFixed(2) }}</template></el-table-column>
          <el-table-column label="方式" min-width="140"><template #default="scope">{{ scope.row.providerCode }}</template></el-table-column>
          <el-table-column label="付款参考" min-width="180"><template #default="scope">{{ scope.row.payerReference || '-' }}</template></el-table-column>
          <el-table-column label="状态" width="140"><template #default="scope"><el-tag :type="paymentType(scope.row.statusCode)">{{ labelStatus(scope.row.statusCode) }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="150"><template #default="scope"><el-button v-if="canReviewPayment(scope.row)" v-hasPermi="['nxr:order:manage']" link type="success" @click="openPaymentAction(scope.row, 'confirm')">确认</el-button><el-button v-if="canReviewPayment(scope.row)" v-hasPermi="['nxr:order:manage']" link type="danger" @click="openPaymentAction(scope.row, 'reject')">驳回</el-button></template></el-table-column>
        </el-table>

        <el-divider content-position="left">物流与进度</el-divider>
        <el-row :gutter="16" class="mb12" v-hasPermi="['nxr:order:manage']">
          <el-col :span="12"><el-form :inline="true" :model="statusForm"><el-form-item label="推进状态"><el-select v-model="statusForm.statusCode" style="width: 180px"><el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item><el-form-item><el-button type="primary" :loading="savingStatus" @click="saveStatus">保存</el-button></el-form-item></el-form></el-col>
          <el-col :span="12"><el-form :inline="true" :model="shipmentForm"><el-form-item label="物流方向"><el-select v-model="shipmentForm.direction" style="width: 100px"><el-option label="收件" value="inbound" /><el-option label="回寄" value="outbound" /></el-select></el-form-item><el-form-item label="承运商"><el-input v-model="shipmentForm.carrierName" style="width: 120px" /></el-form-item><el-form-item label="单号"><el-input v-model="shipmentForm.trackingNumber" style="width: 150px" /></el-form-item><el-form-item><el-button type="primary" :loading="savingShipment" @click="saveShipment">登记物流</el-button></el-form-item></el-form></el-col>
        </el-row>
        <el-table :data="detail.shipments" size="small" border><el-table-column label="方向" width="100"><template #default="scope">{{ scope.row.directionCode === 'inbound' ? '收件' : '回寄' }}</template></el-table-column><el-table-column label="承运商" prop="carrierName" width="130" /><el-table-column label="单号" prop="trackingNumber" min-width="200" /><el-table-column label="状态" width="120"><template #default="scope"><el-tag :type="scope.row.statusCode === 'delivered' ? 'success' : 'info'">{{ scope.row.statusCode }}</el-tag></template></el-table-column><el-table-column label="发件时间" width="170"><template #default="scope">{{ parseTime(scope.row.shippedAt) }}</template></el-table-column><el-table-column label="操作" width="110"><template #default="scope"><el-button v-if="!scope.row.deliveredAt" v-hasPermi="['nxr:order:manage']" link type="success" @click="markDelivered(scope.row)">签收</el-button></template></el-table-column></el-table>

        <el-divider content-position="left">客户可见进度</el-divider>
        <el-timeline><el-timeline-item v-for="event in detail.timeline" :key="event.id" :timestamp="parseTime(event.createdAt)"><strong>{{ event.title }}</strong><p v-if="event.detail" class="timeline-detail">{{ event.detail }}</p></el-timeline-item></el-timeline>
      </template>
    </el-dialog>

    <el-dialog v-model="paymentDialogOpen" :title="paymentAction === 'confirm' ? '确认收款' : '驳回收款'" width="460px" append-to-body>
      <el-form :model="paymentForm" label-width="110px"><el-form-item v-if="paymentAction === 'confirm'" label="交易号"><el-input v-model="paymentForm.providerTransactionId" placeholder="可选" /></el-form-item><el-form-item :label="paymentAction === 'confirm' ? '备注' : '驳回原因'" required><el-input v-model="paymentForm.note" type="textarea" :rows="3" /></el-form-item></el-form>
      <template #footer><el-button @click="paymentDialogOpen = false">取消</el-button><el-button :type="paymentAction === 'confirm' ? 'success' : 'danger'" :loading="savingPayment" @click="savePaymentAction">确认</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup name="NxrOrders">
import {
  confirmGradingPayment,
  createGradingShipment,
  getGradingOrder,
  linkGradingOrderItem,
  listGradingOrders,
  markGradingShipmentDelivered,
  rejectGradingPayment,
  updateGradingOrderStatus
} from '@/api/nxr/orders'

const { proxy } = getCurrentInstance()
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const detailOpen = ref(false)
const detail = ref(null)
const paymentDialogOpen = ref(false)
const paymentAction = ref('confirm')
const activePayment = ref(null)
const savingPayment = ref(false)
const savingStatus = ref(false)
const savingShipment = ref(false)
const linkingItemId = ref(null)
const submissionLinks = reactive({})
const queryParams = reactive({ page: 1, pageSize: 20, status: undefined, query: undefined })
const statusForm = reactive({ statusCode: '' })
const shipmentForm = reactive({ direction: 'inbound', carrierName: '', trackingNumber: '', note: '' })
const paymentForm = reactive({ providerTransactionId: '', note: '' })

const statusOptions = [
  { value: 'awaiting_payment', label: '待付款' }, { value: 'payment_review', label: '收款审核' }, { value: 'awaiting_inbound', label: '待寄卡' }, { value: 'inbound_shipped', label: '寄往 NXR' }, { value: 'received', label: '已收卡' }, { value: 'grading', label: '评分中' }, { value: 'review', label: '复核中' }, { value: 'completed', label: '评分完成' }, { value: 'return_shipped', label: '已回寄' }, { value: 'delivered', label: '已签收' }, { value: 'cancelled', label: '已取消' }
]

function labelStatus(value) { return statusOptions.find((item) => item.value === value)?.label || value }
function statusType(value) { if (['completed', 'delivered'].includes(value)) return 'success'; if (['cancelled'].includes(value)) return 'danger'; if (['payment_review', 'review'].includes(value)) return 'warning'; return 'info' }
function paymentType(value) { return value === 'confirmed' ? 'success' : value === 'rejected' ? 'danger' : value === 'proof_submitted' ? 'warning' : 'info' }
function canReviewPayment(payment) { return ['pending', 'proof_submitted'].includes(payment.statusCode) }

function loadOrders(resetPage = false) {
  if (resetPage) queryParams.page = 1
  loading.value = true
  return listGradingOrders(queryParams).then((res) => { rows.value = res.data.items; total.value = res.data.total; queryParams.page = res.data.page; queryParams.pageSize = res.data.pageSize }).finally(() => { loading.value = false })
}

function resetQuery() { proxy.resetForm('queryRef'); loadOrders(true) }

function openDetail(orderId) {
  return getGradingOrder(orderId).then((res) => { detail.value = res.data; statusForm.statusCode = detail.value.statusCode; detailOpen.value = true })
}

function refreshDetail() { if (!detail.value) return Promise.resolve(); return openDetail(detail.value.id).then(() => loadOrders()) }

function openPaymentAction(payment, action) { activePayment.value = payment; paymentAction.value = action; paymentForm.providerTransactionId = ''; paymentForm.note = ''; paymentDialogOpen.value = true }

function savePaymentAction() {
  if (!detail.value || !activePayment.value) return
  if (paymentAction.value === 'reject' && !paymentForm.note.trim()) { proxy.$modal.msgWarning('请填写驳回原因'); return }
  savingPayment.value = true
  const call = paymentAction.value === 'confirm' ? confirmGradingPayment : rejectGradingPayment
  const payload = paymentAction.value === 'confirm' ? { providerTransactionId: paymentForm.providerTransactionId, note: paymentForm.note } : { note: paymentForm.note }
  call(detail.value.id, activePayment.value.id, payload).then((res) => { detail.value = res.data; statusForm.statusCode = detail.value.statusCode; paymentDialogOpen.value = false; proxy.$modal.msgSuccess(paymentAction.value === 'confirm' ? '收款已确认' : '收款已驳回'); return loadOrders() }).finally(() => { savingPayment.value = false })
}

function saveStatus() {
  if (!detail.value || !statusForm.statusCode) return
  savingStatus.value = true
  updateGradingOrderStatus(detail.value.id, { statusCode: statusForm.statusCode, detail: '' }).then((res) => { detail.value = res.data; statusForm.statusCode = detail.value.statusCode; proxy.$modal.msgSuccess('订单状态已更新'); return loadOrders() }).finally(() => { savingStatus.value = false })
}

function saveShipment() {
  if (!detail.value || !shipmentForm.carrierName.trim() || !shipmentForm.trackingNumber.trim()) { proxy.$modal.msgWarning('请填写承运商和物流单号'); return }
  savingShipment.value = true
  createGradingShipment(detail.value.id, shipmentForm).then((res) => { detail.value = res.data; statusForm.statusCode = detail.value.statusCode; shipmentForm.carrierName = ''; shipmentForm.trackingNumber = ''; shipmentForm.note = ''; proxy.$modal.msgSuccess('物流已登记'); return loadOrders() }).finally(() => { savingShipment.value = false })
}

function markDelivered(shipment) {
  if (!detail.value) return
  proxy.$modal.confirm('确认该物流已签收？').then(() => markGradingShipmentDelivered(detail.value.id, shipment.id)).then((res) => { detail.value = res.data; statusForm.statusCode = detail.value.statusCode; proxy.$modal.msgSuccess('已更新签收状态'); return loadOrders() }).catch(() => {})
}

function linkSubmission(item) {
  if (!detail.value || !submissionLinks[item.id]) { proxy.$modal.msgWarning('请输入评分条目 ID'); return }
  linkingItemId.value = item.id
  linkGradingOrderItem(detail.value.id, item.id, submissionLinks[item.id]).then((res) => { detail.value = res.data; statusForm.statusCode = detail.value.statusCode; proxy.$modal.msgSuccess('已关联评分条目') }).finally(() => { linkingItemId.value = null })
}

loadOrders()
</script>

<style scoped>
small,.timeline-detail{color:#909399}.link-submission{display:flex;align-items:center;gap:8px}.link-submission .el-input-number{width:120px}.timeline-detail{margin:5px 0 0;line-height:1.5}
</style>
