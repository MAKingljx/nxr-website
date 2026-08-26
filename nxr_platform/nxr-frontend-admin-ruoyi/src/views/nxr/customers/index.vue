<template>
  <main class="nxr-workspace customer-workspace">
    <nxr-page-header
      kicker="COLLECTOR ACCOUNTS"
      title="客户管理"
      :summary="`共 ${formatNumber(total)} 位用户`"
    >
      <template #actions>
        <el-tooltip content="刷新用户列表" placement="bottom">
          <el-button :icon="Refresh" circle :loading="loading" aria-label="刷新用户列表" @click="loadCustomers" />
        </el-tooltip>
      </template>
    </nxr-page-header>

    <nxr-server-data-workbench
      :loading="loading"
      :error="loadError"
      :empty="!loading && !loadError && rows.length === 0"
      :total="total"
      :page="queryParams.page"
      :page-size="queryParams.pageSize"
      :show-reset="hasQuery"
      empty-title="没有找到用户"
      empty-description="请调整状态或搜索条件后重试。"
      aria-label="用户数据列表"
      @query="loadCustomers(true)"
      @reset="resetQuery"
      @retry="loadCustomers"
      @page-change="handleCustomerPageChange"
    >
      <template #filters>
        <el-segmented v-model="queryParams.status" :options="statusOptions" @change="loadCustomers(true)" />
        <div class="directory-search">
          <el-input
            v-model="queryParams.query"
            :prefix-icon="Search"
            clearable
            placeholder="搜索昵称、邮箱或手机"
            aria-label="搜索昵称、邮箱或手机"
            @clear="loadCustomers(true)"
          />
        </div>
      </template>
      <template #filter-actions>
        <el-button type="primary" :icon="Search" :loading="loading" native-type="submit">搜索</el-button>
        <el-button v-if="hasQuery" :icon="RefreshLeft" :disabled="loading" @click="resetQuery">重置</el-button>
      </template>

      <customer-directory
        :rows="rows"
        :can-manage="canManage"
        :status-changing-id="statusChangingId"
        @open-detail="openDetail"
        @toggle-status="confirmStatus"
      />
    </nxr-server-data-workbench>

    <customer-detail-drawer
      v-model="detailOpen"
      :detail="detail"
      :loading="detailLoading"
      :can-manage="canManage"
      :status-changing-id="statusChangingId"
      :revoking-sessions="revokingSessions"
      @toggle-status="confirmStatus"
      @revoke-sessions="confirmRevokeSessions"
      @change-type="changeCustomerType"
    />
  </main>
</template>

<script setup name="NxrCustomers">
import { Refresh, RefreshLeft, Search } from '@element-plus/icons-vue'
import auth from '@/plugins/auth'
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import NxrServerDataWorkbench from '@/components/NxrWorkspace/ServerDataWorkbench.vue'
import { getCustomer, listCustomers, revokeCustomerSessions, updateCustomerStatus, updateCustomerType } from '@/api/nxr/customers'
import CustomerDetailDrawer from './components/CustomerDetailDrawer.vue'
import CustomerDirectory from './components/CustomerDirectory.vue'

const { proxy } = getCurrentInstance()
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const loadError = ref('')
const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const statusChangingId = ref(null)
const revokingSessions = ref(false)
const queryParams = reactive({ page: 1, pageSize: 20, status: '', query: '' })

const statusOptions = [
  { label: '全部用户', value: '' },
  { label: '正常', value: 'active' },
  { label: '已停用', value: 'inactive' }
]
const canManage = auth.hasPermi('nxr:customer:manage')
const hasQuery = computed(() => Boolean(queryParams.status || queryParams.query))

async function loadCustomers(resetPage = false) {
  if (resetPage) queryParams.page = 1
  loading.value = true
  loadError.value = ''
  try {
    const response = await listCustomers(queryParams)
    rows.value = response.data.items
    total.value = response.data.total
    queryParams.page = response.data.page
    queryParams.pageSize = response.data.pageSize
  } catch {
    loadError.value = '暂时无法读取用户数据，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function handleCustomerPageChange(page, pageSize) {
  queryParams.page = page
  queryParams.pageSize = pageSize
  loadCustomers()
}

function resetQuery() {
  queryParams.status = ''
  queryParams.query = ''
  loadCustomers(true)
}

async function openDetail(customerId) {
  detailOpen.value = true
  detailLoading.value = true
  detail.value = null
  try {
    const response = await getCustomer(customerId)
    detail.value = response.data
  } finally {
    detailLoading.value = false
  }
}

function confirmStatus(customer) {
  const nextActive = !customer.active
  const action = nextActive ? '恢复' : '停用'
  const warning = nextActive
    ? '确认恢复该用户账号？'
    : '确认停用该用户账号？当前登录会话将退出，持卡和订单记录不会删除。'
  proxy.$modal.confirm(warning).then(async () => {
    statusChangingId.value = customer.id
    const response = await updateCustomerStatus(customer.id, nextActive)
    if (detail.value?.customer.id === customer.id) detail.value = response.data
    proxy.$modal.msgSuccess(`账号已${action}`)
    await loadCustomers()
  }).catch(() => {}).finally(() => {
    statusChangingId.value = null
  })
}

function confirmRevokeSessions() {
  if (!detail.value) return
  proxy.$modal.confirm('确认让该用户在全部设备退出登录？账号、持卡和订单记录不会改变。').then(async () => {
    revokingSessions.value = true
    const response = await revokeCustomerSessions(detail.value.customer.id)
    detail.value.customer.activeSessionCount = 0
    proxy.$modal.msgSuccess(`已退出 ${response.data.revokedSessions} 个会话`)
    await loadCustomers()
  }).catch(() => {}).finally(() => {
    revokingSessions.value = false
  })
}

async function changeCustomerType(customer, accountTypeCode) {
  if (!customer || customer.accountTypeCode === accountTypeCode) return
  const label = accountTypeCode === 'merchant' ? '商户' : '普通客户'
  try {
    const response = await updateCustomerType(customer.id, accountTypeCode)
    if (detail.value?.customer.id === customer.id) detail.value = response.data
    proxy.$modal.msgSuccess(`账号类型已改为${label}`)
    await loadCustomers()
  } catch {
    proxy.$modal.msgError('账号类型更新失败')
  }
}

function formatNumber(value) {
  return new Intl.NumberFormat('zh-CN').format(value || 0)
}

loadCustomers()
</script>

<style scoped lang="scss">
.customer-workspace {
  overflow-x: hidden;
}

.directory-search {
  width: min(100%, 330px);
}

.directory-search .el-input {
  width: 100%;
}

:deep(.el-segmented) {
  --el-segmented-item-selected-bg-color: var(--nxr-surface);
  --el-segmented-item-selected-color: var(--nxr-accent);
  --el-segmented-bg-color: var(--nxr-surface-muted);
}

:deep(.nxr-server-data-workbench .directory-table) {
  border: 0;
  border-radius: 0;
}

@media (max-width: 900px) {
  .directory-search {
    width: 100%;
  }

  .directory-search .el-input {
    width: 100%;
  }
}

</style>
