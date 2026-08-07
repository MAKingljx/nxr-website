<template>
  <main class="nxr-workspace customer-workspace">
    <nxr-page-header
      kicker="COLLECTOR ACCOUNTS"
      title="用户管理"
      :summary="`共 ${formatNumber(total)} 位用户`"
    >
      <template #actions>
        <el-tooltip content="刷新用户列表" placement="bottom">
          <el-button :icon="Refresh" circle :loading="loading" aria-label="刷新用户列表" @click="loadCustomers" />
        </el-tooltip>
      </template>
    </nxr-page-header>

    <section class="directory-toolbar" aria-label="用户筛选">
      <el-segmented v-model="queryParams.status" :options="statusOptions" @change="loadCustomers(true)" />
      <div class="directory-search">
        <el-input
          v-model="queryParams.query"
          :prefix-icon="Search"
          clearable
          placeholder="搜索昵称、邮箱或手机"
          @keyup.enter="loadCustomers(true)"
          @clear="loadCustomers(true)"
        />
        <el-button type="primary" :icon="Search" @click="loadCustomers(true)">搜索</el-button>
        <el-button v-if="hasQuery" :icon="RefreshLeft" @click="resetQuery">重置</el-button>
      </div>
    </section>

    <customer-directory
      :rows="rows"
      :loading="loading"
      :can-manage="canManage"
      :status-changing-id="statusChangingId"
      @open-detail="openDetail"
      @toggle-status="confirmStatus"
    />

    <pagination
      v-show="total > 0"
      :total="total"
      v-model:page="queryParams.page"
      v-model:limit="queryParams.pageSize"
      @pagination="loadCustomers"
    />

    <customer-detail-drawer
      v-model="detailOpen"
      :detail="detail"
      :loading="detailLoading"
      :can-manage="canManage"
      :status-changing-id="statusChangingId"
      :revoking-sessions="revokingSessions"
      @toggle-status="confirmStatus"
      @revoke-sessions="confirmRevokeSessions"
    />
  </main>
</template>

<script setup name="NxrCustomers">
import { Refresh, RefreshLeft, Search } from '@element-plus/icons-vue'
import auth from '@/plugins/auth'
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import { getCustomer, listCustomers, revokeCustomerSessions, updateCustomerStatus } from '@/api/nxr/customers'
import CustomerDetailDrawer from './components/CustomerDetailDrawer.vue'
import CustomerDirectory from './components/CustomerDirectory.vue'

const { proxy } = getCurrentInstance()
const rows = ref([])
const total = ref(0)
const loading = ref(false)
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
  try {
    const response = await listCustomers(queryParams)
    rows.value = response.data.items
    total.value = response.data.total
    queryParams.page = response.data.page
    queryParams.pageSize = response.data.pageSize
  } finally {
    loading.value = false
  }
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

function formatNumber(value) {
  return new Intl.NumberFormat('zh-CN').format(value || 0)
}

loadCustomers()
</script>

<style scoped lang="scss">
.customer-workspace {
  overflow-x: hidden;
}

.directory-toolbar {
  display: flex;
  min-height: 70px;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 14px 16px;
  border: 1px solid var(--nxr-border);
  border-bottom: 0;
  border-radius: 8px 8px 0 0;
  background: var(--nxr-surface);
}

.directory-search {
  display: flex;
  width: min(100%, 560px);
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.directory-search .el-input {
  width: min(100%, 330px);
}

:deep(.el-segmented) {
  --el-segmented-item-selected-bg-color: var(--nxr-surface);
  --el-segmented-item-selected-color: var(--nxr-accent);
  --el-segmented-bg-color: var(--nxr-surface-muted);
}

:deep(.pagination-container) {
  margin-top: 14px;
  background: transparent;
}

@media (max-width: 900px) {
  .directory-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .directory-search {
    width: 100%;
    justify-content: flex-start;
  }

  .directory-search .el-input {
    width: 100%;
  }
}

@media (max-width: 560px) {
  .directory-search {
    align-items: stretch;
    flex-wrap: wrap;
  }

  .directory-search .el-input {
    flex-basis: 100%;
  }
}
</style>
