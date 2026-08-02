<template>
  <div class="app-container">
    <el-form ref="queryRef" :model="queryParams" :inline="true" @submit.prevent>
      <el-form-item label="账号状态" prop="status">
        <el-select v-model="queryParams.status" clearable placeholder="全部状态" style="width: 150px">
          <el-option label="正常" value="active" />
          <el-option label="停用" value="inactive" />
        </el-select>
      </el-form-item>
      <el-form-item label="关键词" prop="query">
        <el-input
          v-model="queryParams.query"
          clearable
          placeholder="邮箱 / 昵称 / 手机"
          style="width: 260px"
          @keyup.enter="loadCustomers(true)"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="loadCustomers(true)">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows">
      <el-table-column label="客户" min-width="220" show-overflow-tooltip>
        <template #default="scope">
          <div class="customer-name">{{ scope.row.displayName }}</div>
          <small>{{ scope.row.email }}</small>
        </template>
      </el-table-column>
      <el-table-column label="手机" prop="mobile" min-width="135">
        <template #default="scope">{{ scope.row.mobile || '-' }}</template>
      </el-table-column>
      <el-table-column label="当前持卡" prop="activeCardCount" width="95" align="center" />
      <el-table-column label="历史持卡" prop="ownershipCount" width="95" align="center" />
      <el-table-column label="订单" prop="orderCount" width="80" align="center" />
      <el-table-column label="有效会话" prop="activeSessionCount" width="95" align="center" />
      <el-table-column label="最近登录" width="170" align="center">
        <template #default="scope">{{ scope.row.lastLoginAt ? parseTime(scope.row.lastLoginAt) : '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="scope">
          <el-tag :type="scope.row.active ? 'success' : 'info'">{{ scope.row.active ? '正常' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" align="center" fixed="right">
        <template #default="scope">
          <el-button link type="primary" icon="View" @click="openDetail(scope.row.id)">详情</el-button>
          <el-button
            v-hasPermi="['nxr:customer:manage']"
            link
            :type="scope.row.active ? 'danger' : 'success'"
            @click="confirmStatus(scope.row)"
          >
            {{ scope.row.active ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination
      v-show="total > 0"
      :total="total"
      v-model:page="queryParams.page"
      v-model:limit="queryParams.pageSize"
      @pagination="loadCustomers"
    />

    <el-dialog v-model="detailOpen" title="客户账号详情" width="1080px" append-to-body>
      <template v-if="detail">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="昵称">{{ detail.customer.displayName }}</el-descriptions-item>
          <el-descriptions-item label="邮箱">{{ detail.customer.email }}</el-descriptions-item>
          <el-descriptions-item label="手机">{{ detail.customer.mobile || '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ parseTime(detail.customer.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="最近登录">{{ detail.customer.lastLoginAt ? parseTime(detail.customer.lastLoginAt) : '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="detail.customer.active ? 'success' : 'info'">{{ detail.customer.active ? '正常' : '停用' }}</el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <div class="detail-actions" v-hasPermi="['nxr:customer:manage']">
          <el-button
            :type="detail.customer.active ? 'danger' : 'success'"
            :loading="savingStatus"
            @click="confirmStatus(detail.customer)"
          >
            {{ detail.customer.active ? '停用账号' : '启用账号' }}
          </el-button>
          <el-button icon="SwitchButton" :loading="revokingSessions" @click="confirmRevokeSessions">使全部会话失效</el-button>
        </div>

        <el-divider content-position="left">持卡记录</el-divider>
        <el-table :data="detail.cards" size="small" border>
          <el-table-column label="证书编号" prop="certId" min-width="135" />
          <el-table-column label="卡片" min-width="210" show-overflow-tooltip>
            <template #default="scope">{{ scope.row.cardName || '-' }}<small v-if="scope.row.brandName"> · {{ scope.row.brandName }}</small></template>
          </el-table-column>
          <el-table-column label="评级" width="110">
            <template #default="scope">{{ scope.row.finalGradeValue ?? '-' }} {{ scope.row.finalGradeLabel || '' }}</template>
          </el-table-column>
          <el-table-column label="归属状态" prop="statusCode" width="110" />
          <el-table-column label="可见性" prop="visibilityCode" width="100" />
          <el-table-column label="绑定时间" width="170">
            <template #default="scope">{{ parseTime(scope.row.boundAt) }}</template>
          </el-table-column>
          <el-table-column label="释放时间" width="170">
            <template #default="scope">{{ scope.row.releasedAt ? parseTime(scope.row.releasedAt) : '-' }}</template>
          </el-table-column>
        </el-table>

        <el-divider content-position="left">卡片流转历史</el-divider>
        <el-table :data="detail.ownershipEvents" size="small" border>
          <el-table-column label="证书编号" prop="certId" min-width="135" />
          <el-table-column label="事件" prop="eventTypeCode" width="110" />
          <el-table-column label="从" min-width="150"><template #default="scope">{{ scope.row.fromDisplayName || '-' }}</template></el-table-column>
          <el-table-column label="到" min-width="150"><template #default="scope">{{ scope.row.toDisplayName || '-' }}</template></el-table-column>
          <el-table-column label="备注" prop="message" min-width="200" show-overflow-tooltip />
          <el-table-column label="时间" width="170"><template #default="scope">{{ parseTime(scope.row.createdAt) }}</template></el-table-column>
        </el-table>

        <el-divider content-position="left">送评订单</el-divider>
        <el-table :data="detail.orders" size="small" border>
          <el-table-column label="订单号" prop="orderNo" min-width="150" />
          <el-table-column label="状态" prop="statusCode" min-width="130" />
          <el-table-column label="服务" prop="serviceLevelCode" width="100" />
          <el-table-column label="卡数" prop="totalCardCount" width="80" align="center" />
          <el-table-column label="金额" width="125" align="right">
            <template #default="scope">{{ scope.row.currencyCode }} {{ Number(scope.row.totalAmount).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column label="创建时间" width="170"><template #default="scope">{{ parseTime(scope.row.createdAt) }}</template></el-table-column>
        </el-table>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="NxrCustomers">
import { getCustomer, listCustomers, revokeCustomerSessions, updateCustomerStatus } from '@/api/nxr/customers'

const { proxy } = getCurrentInstance()
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const detailOpen = ref(false)
const detail = ref(null)
const savingStatus = ref(false)
const revokingSessions = ref(false)
const queryParams = reactive({ page: 1, pageSize: 20, status: undefined, query: undefined })

function loadCustomers(resetPage = false) {
  if (resetPage) queryParams.page = 1
  loading.value = true
  return listCustomers(queryParams)
    .then((res) => {
      rows.value = res.data.items
      total.value = res.data.total
      queryParams.page = res.data.page
      queryParams.pageSize = res.data.pageSize
    })
    .finally(() => { loading.value = false })
}

function resetQuery() {
  proxy.resetForm('queryRef')
  loadCustomers(true)
}

function openDetail(customerId) {
  return getCustomer(customerId).then((res) => {
    detail.value = res.data
    detailOpen.value = true
  })
}

function confirmStatus(customer) {
  const nextActive = !customer.active
  const action = nextActive ? '启用' : '停用'
  const warning = nextActive
    ? '确认启用该客户账号？'
    : '确认停用该客户账号？停用会使现有登录会话失效，但不会删除持卡或订单数据。'
  proxy.$modal.confirm(warning).then(() => {
    savingStatus.value = true
    return updateCustomerStatus(customer.id, nextActive)
  }).then((res) => {
    if (detail.value?.customer.id === customer.id) detail.value = res.data
    proxy.$modal.msgSuccess(`账号已${action}`)
    return loadCustomers()
  }).finally(() => {
    savingStatus.value = false
  }).catch(() => {})
}

function confirmRevokeSessions() {
  if (!detail.value) return
  proxy.$modal.confirm('确认使该客户的全部登录会话失效？账号、持卡和订单数据不会改变。').then(() => {
    revokingSessions.value = true
    return revokeCustomerSessions(detail.value.customer.id)
  }).then((res) => {
    detail.value.customer.activeSessionCount = 0
    proxy.$modal.msgSuccess(`已失效 ${res.data.revokedSessions} 个会话`)
    return loadCustomers()
  }).finally(() => {
    revokingSessions.value = false
  }).catch(() => {})
}

loadCustomers()
</script>

<style scoped>
.customer-name{font-weight:600}.detail-actions{display:flex;gap:10px;margin-top:16px}small{color:#909399}
</style>
