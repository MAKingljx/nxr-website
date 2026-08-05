<template>
  <main class="app-container customer-workspace">
    <header class="workspace-header">
      <div>
        <p class="workspace-header__kicker">COLLECTOR ACCOUNTS</p>
        <h1>用户管理</h1>
        <p>共 {{ formatNumber(total) }} 位用户</p>
      </div>
      <el-tooltip content="刷新用户列表" placement="bottom">
        <el-button :icon="Refresh" circle :loading="loading" aria-label="刷新用户列表" @click="loadCustomers" />
      </el-tooltip>
    </header>

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

    <section class="directory-table">
      <el-table v-loading="loading" :data="rows" row-key="id" class="desktop-user-table">
        <el-table-column label="用户" min-width="280" fixed="left">
          <template #default="scope">
            <button type="button" class="identity-cell" @click="openDetail(scope.row.id)">
              <el-avatar :size="38" :style="{ backgroundColor: avatarColor(scope.row.id) }">
                {{ avatarText(scope.row.displayName) }}
              </el-avatar>
              <span>
                <strong>{{ scope.row.displayName || '未设置昵称' }}</strong>
                <small>{{ scope.row.email }}</small>
              </span>
            </button>
          </template>
        </el-table-column>
        <el-table-column label="卡片" width="132" align="center">
          <template #default="scope">
            <span class="count-value">{{ scope.row.activeCardCount }}</span>
            <small class="count-label">当前 · {{ scope.row.ownershipCount }} 次历史</small>
          </template>
        </el-table-column>
        <el-table-column label="订单" width="88" align="center">
          <template #default="scope">
            <span class="count-value">{{ scope.row.orderCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最近登录" min-width="165">
          <template #default="scope">
            <span class="date-value">{{ scope.row.lastLoginAt ? formatDate(scope.row.lastLoginAt) : '尚未登录' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="108" align="center">
          <template #default="scope">
            <el-tooltip :content="scope.row.active ? '账号正常' : '账号已停用'" placement="top">
              <el-switch
                v-hasPermi="['nxr:customer:manage']"
                :model-value="scope.row.active"
                :loading="statusChangingId === scope.row.id"
                :aria-label="scope.row.active ? '停用账号' : '启用账号'"
                @change="confirmStatus(scope.row)"
              />
            </el-tooltip>
            <el-tag v-if="!canManage" :type="scope.row.active ? 'success' : 'info'" effect="plain">
              {{ scope.row.active ? '正常' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="详情" width="72" align="center" fixed="right">
          <template #default="scope">
            <el-tooltip content="查看用户详情" placement="top">
              <el-button link type="primary" :icon="View" aria-label="查看用户详情" @click="openDetail(scope.row.id)" />
            </el-tooltip>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有找到符合条件的用户" :image-size="88" />
        </template>
      </el-table>

      <div v-loading="loading" class="mobile-user-list">
        <article v-for="customer in rows" :key="customer.id" class="mobile-user-item">
          <header>
            <button type="button" class="identity-cell" @click="openDetail(customer.id)">
              <el-avatar :size="40" :style="{ backgroundColor: avatarColor(customer.id) }">
                {{ avatarText(customer.displayName) }}
              </el-avatar>
              <span>
                <strong>{{ customer.displayName || '未设置昵称' }}</strong>
                <small>{{ customer.email }}</small>
              </span>
            </button>
            <el-switch
              v-if="canManage"
              :model-value="customer.active"
              :loading="statusChangingId === customer.id"
              :aria-label="customer.active ? '停用账号' : '启用账号'"
              @change="confirmStatus(customer)"
            />
            <el-tag v-else :type="customer.active ? 'success' : 'info'" effect="plain">
              {{ customer.active ? '正常' : '停用' }}
            </el-tag>
          </header>
          <div class="mobile-user-metrics">
            <span><strong>{{ customer.activeCardCount }}</strong>当前持卡</span>
            <span><strong>{{ customer.orderCount }}</strong>送评订单</span>
            <span><strong>{{ customer.lastLoginAt ? formatDate(customer.lastLoginAt) : '尚未登录' }}</strong>最近登录</span>
          </div>
          <button
            type="button"
            class="mobile-detail-link"
            :aria-label="`查看 ${customer.displayName || '用户'} 详情`"
            @click="openDetail(customer.id)"
          >
            查看用户详情
            <el-icon><ArrowRight /></el-icon>
          </button>
        </article>
        <el-empty v-if="!loading && !rows.length" description="没有找到符合条件的用户" :image-size="72" />
      </div>
    </section>

    <pagination
      v-show="total > 0"
      :total="total"
      v-model:page="queryParams.page"
      v-model:limit="queryParams.pageSize"
      @pagination="loadCustomers"
    />

    <el-drawer
      v-model="detailOpen"
      class="customer-drawer"
      :size="drawerSize"
      destroy-on-close
      append-to-body
    >
      <template #header>
        <div v-if="detail" class="drawer-identity">
          <el-avatar :size="46" :style="{ backgroundColor: avatarColor(detail.customer.id) }">
            {{ avatarText(detail.customer.displayName) }}
          </el-avatar>
          <div>
            <strong>{{ detail.customer.displayName || '未设置昵称' }}</strong>
            <span>{{ detail.customer.email }}</span>
          </div>
          <el-tag :type="detail.customer.active ? 'success' : 'info'" effect="plain">
            {{ detail.customer.active ? '正常' : '已停用' }}
          </el-tag>
        </div>
        <span v-else>用户详情</span>
      </template>

      <div v-loading="detailLoading" class="drawer-content">
        <template v-if="detail">
          <section class="detail-metrics" aria-label="用户数据概览">
            <div><strong>{{ detail.customer.activeCardCount }}</strong><span>当前持卡</span></div>
            <div><strong>{{ detail.customer.ownershipCount }}</strong><span>流转记录</span></div>
            <div><strong>{{ detail.customer.orderCount }}</strong><span>送评订单</span></div>
            <div><strong>{{ detail.customer.activeSessionCount }}</strong><span>有效会话</span></div>
          </section>

          <el-tabs v-model="detailTab" class="detail-tabs">
            <el-tab-pane label="账号资料" name="profile">
              <dl class="profile-list">
                <div><dt>昵称</dt><dd>{{ detail.customer.displayName || '未填写' }}</dd></div>
                <div><dt>邮箱</dt><dd>{{ detail.customer.email }}</dd></div>
                <div><dt>手机</dt><dd>{{ detail.customer.mobile || '未填写' }}</dd></div>
                <div><dt>加入时间</dt><dd>{{ formatDate(detail.customer.createdAt) }}</dd></div>
                <div><dt>最近登录</dt><dd>{{ detail.customer.lastLoginAt ? formatDate(detail.customer.lastLoginAt) : '尚未登录' }}</dd></div>
                <div><dt>账号编号</dt><dd>#{{ detail.customer.id }}</dd></div>
              </dl>

              <div v-hasPermi="['nxr:customer:manage']" class="account-actions">
                <el-button
                  :type="detail.customer.active ? 'danger' : 'success'"
                  plain
                  :loading="statusChangingId === detail.customer.id"
                  @click="confirmStatus(detail.customer)"
                >
                  {{ detail.customer.active ? '停用账号' : '恢复账号' }}
                </el-button>
                <el-button :icon="Key" :loading="revokingSessions" @click="confirmRevokeSessions">
                  退出全部设备
                </el-button>
              </div>
            </el-tab-pane>

            <el-tab-pane :label="`持卡 ${detail.cards.length}`" name="cards">
              <el-table :data="detail.cards" size="small">
                <el-table-column label="证书" prop="certId" min-width="130" />
                <el-table-column label="卡片" min-width="210" show-overflow-tooltip>
                  <template #default="scope">
                    <strong class="detail-card-name">{{ scope.row.cardName || '未匹配卡片资料' }}</strong>
                    <small class="detail-card-brand">{{ scope.row.brandName || '-' }}</small>
                  </template>
                </el-table-column>
                <el-table-column label="评级" width="92">
                  <template #default="scope">{{ formatGrade(scope.row) }}</template>
                </el-table-column>
                <el-table-column label="状态" width="105">
                  <template #default="scope">
                    <el-tag :type="scope.row.statusCode === 'active' ? 'success' : 'info'" effect="plain">
                      {{ ownershipLabel(scope.row.statusCode) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="绑定时间" width="160">
                  <template #default="scope">{{ formatDate(scope.row.boundAt) }}</template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <el-tab-pane :label="`流转 ${detail.ownershipEvents.length}`" name="history">
              <el-empty v-if="!detail.ownershipEvents.length" description="暂无卡片流转记录" :image-size="72" />
              <el-timeline v-else class="ownership-timeline">
                <el-timeline-item
                  v-for="event in detail.ownershipEvents"
                  :key="event.id"
                  :timestamp="formatDate(event.createdAt)"
                  placement="top"
                >
                  <div class="timeline-entry">
                    <div>
                      <el-tag size="small" effect="plain">{{ eventLabel(event.eventTypeCode) }}</el-tag>
                      <strong>{{ event.certId }}</strong>
                    </div>
                    <p>{{ transferLabel(event) }}</p>
                    <small v-if="event.message">{{ event.message }}</small>
                  </div>
                </el-timeline-item>
              </el-timeline>
            </el-tab-pane>

            <el-tab-pane :label="`订单 ${detail.orders.length}`" name="orders">
              <el-table :data="detail.orders" size="small">
                <el-table-column label="订单号" prop="orderNo" min-width="150" />
                <el-table-column label="状态" min-width="125">
                  <template #default="scope">
                    <el-tag effect="plain">{{ orderStatusLabel(scope.row.statusCode) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="卡数" prop="totalCardCount" width="74" align="center" />
                <el-table-column label="金额" width="125" align="right">
                  <template #default="scope">{{ formatAmount(scope.row) }}</template>
                </el-table-column>
                <el-table-column label="创建时间" width="160">
                  <template #default="scope">{{ formatDate(scope.row.createdAt) }}</template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </template>
      </div>
    </el-drawer>
  </main>
</template>

<script setup name="NxrCustomers">
import { Key, Refresh, RefreshLeft, Search, View } from '@element-plus/icons-vue'
import { useWindowSize } from '@vueuse/core'
import auth from '@/plugins/auth'
import { getCustomer, listCustomers, revokeCustomerSessions, updateCustomerStatus } from '@/api/nxr/customers'
import { parseTime } from '@/utils/ruoyi'

const { proxy } = getCurrentInstance()
const { width } = useWindowSize()
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const detailTab = ref('profile')
const statusChangingId = ref(null)
const revokingSessions = ref(false)
const queryParams = reactive({ page: 1, pageSize: 20, status: '', query: '' })

const statusOptions = [
  { label: '全部用户', value: '' },
  { label: '正常', value: 'active' },
  { label: '已停用', value: 'inactive' }
]
const avatarPalette = ['#386f73', '#596f91', '#8a694a', '#6e5d86', '#4e785c']
const canManage = auth.hasPermi('nxr:customer:manage')
const hasQuery = computed(() => Boolean(queryParams.status || queryParams.query))
const drawerSize = computed(() => width.value < 760 ? '100%' : '720px')

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
  detailTab.value = 'profile'
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

function formatDate(value) {
  return value ? parseTime(value, '{y}-{m}-{d} {h}:{i}') : '-'
}

function avatarText(name) {
  return (name || 'U').trim().slice(0, 1).toUpperCase()
}

function avatarColor(id) {
  return avatarPalette[Math.abs(Number(id) || 0) % avatarPalette.length]
}

function formatGrade(card) {
  if (card.finalGradeValue === null || card.finalGradeValue === undefined) return '-'
  return `${Number(card.finalGradeValue).toFixed(1)} ${card.finalGradeLabel || ''}`.trim()
}

function ownershipLabel(status) {
  return ({ active: '持有中', released: '已释放', transferred: '已转让' })[status] || status || '-'
}

function eventLabel(type) {
  return ({ bound: '首次绑定', transferred: '卡片转让', released: '解除绑定' })[type] || type || '流转记录'
}

function transferLabel(event) {
  const from = event.fromDisplayName || '未绑定'
  const to = event.toDisplayName || '未绑定'
  return `${from} → ${to}`
}

function orderStatusLabel(status) {
  return ({
    draft: '待提交', awaiting_payment: '待付款', payment_review: '付款审核',
    inbound_shipped: '寄送中', received: '已收件', grading: '评级中',
    completed: '已完成', return_shipped: '回寄中', delivered: '已送达', cancelled: '已取消'
  })[status] || status || '-'
}

function formatAmount(order) {
  const amount = Number(order.totalAmount)
  return `${order.currencyCode || ''} ${Number.isFinite(amount) ? amount.toFixed(2) : '-'}`.trim()
}

loadCustomers()
</script>

<style scoped lang="scss">
.customer-workspace {
  min-height: 100%;
  padding: 26px 28px 34px;
  background: #f4f7f6;
}

.workspace-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 22px;
}

.workspace-header__kicker {
  margin: 0 0 6px;
  color: #16766e;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0;
}

.workspace-header h1 {
  margin: 0;
  color: #17221f;
  font-size: 27px;
  line-height: 1.2;
  letter-spacing: 0;
}

.workspace-header p:last-child {
  margin: 8px 0 0;
  color: #707b77;
  font-size: 13px;
}

.directory-toolbar {
  display: flex;
  min-height: 70px;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 14px 16px;
  border: 1px solid #e0e7e4;
  border-bottom: 0;
  border-radius: 8px 8px 0 0;
  background: #ffffff;
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

.directory-table {
  overflow: hidden;
  border: 1px solid #e0e7e4;
  border-radius: 0 0 8px 8px;
  background: #ffffff;
}

.mobile-user-list {
  display: none;
}

.identity-cell {
  display: inline-flex;
  max-width: 100%;
  align-items: center;
  gap: 11px;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.identity-cell > span:last-child {
  min-width: 0;
}

.identity-cell strong,
.identity-cell small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.identity-cell strong {
  color: #22302c;
  font-size: 13px;
}

.identity-cell small {
  margin-top: 4px;
  color: #85908c;
  font-size: 11px;
}

.muted-value,
.date-value {
  color: #68736f;
  font-size: 12px;
}

.count-value,
.count-label {
  display: block;
}

.count-value {
  color: #26332f;
  font-size: 14px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.count-label {
  margin-top: 3px;
  color: #98a19e;
  font-size: 10px;
}

:deep(.el-segmented) {
  --el-segmented-item-selected-bg-color: #ffffff;
  --el-segmented-item-selected-color: #156d66;
  --el-segmented-bg-color: #eef3f1;
}

:deep(.el-table__inner-wrapper::before) {
  display: none;
}

:deep(.el-table th.el-table__cell) {
  height: 44px;
  background: #f7f9f8;
  color: #66726e;
  font-size: 12px;
}

:deep(.el-table td.el-table__cell) {
  height: 62px;
  border-bottom-color: #edf1f0;
}

:deep(.pagination-container) {
  margin-top: 14px;
  background: transparent;
}

.drawer-identity {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
}

.drawer-identity > div {
  min-width: 0;
  flex: 1;
}

.drawer-identity strong,
.drawer-identity span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drawer-identity strong {
  color: #1f2d29;
  font-size: 16px;
}

.drawer-identity span {
  margin-top: 3px;
  color: #808b87;
  font-size: 12px;
}

.drawer-content {
  min-height: 280px;
}

.detail-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  overflow: hidden;
  margin-bottom: 18px;
  border: 1px solid #e2e8e6;
  border-radius: 8px;
  background: #e2e8e6;
}

.detail-metrics div {
  min-width: 0;
  padding: 16px 10px;
  background: #ffffff;
  text-align: center;
}

.detail-metrics strong,
.detail-metrics span {
  display: block;
}

.detail-metrics strong {
  color: #1f544f;
  font-size: 21px;
  font-variant-numeric: tabular-nums;
}

.detail-metrics span {
  margin-top: 5px;
  color: #7d8884;
  font-size: 11px;
}

.profile-list {
  margin: 2px 0 0;
}

.profile-list div {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr);
  gap: 18px;
  padding: 14px 4px;
  border-bottom: 1px solid #edf1f0;
}

.profile-list dt,
.profile-list dd {
  margin: 0;
  font-size: 13px;
}

.profile-list dt {
  color: #7d8884;
}

.profile-list dd {
  overflow-wrap: anywhere;
  color: #283531;
}

.account-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 22px;
}

.detail-card-name,
.detail-card-brand {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-card-brand {
  margin-top: 3px;
  color: #8a9491;
  font-size: 11px;
}

.ownership-timeline {
  padding: 12px 4px 0;
}

.timeline-entry {
  padding: 13px 15px;
  border: 1px solid #e3e9e7;
  border-radius: 7px;
  background: #fafcfb;
}

.timeline-entry > div {
  display: flex;
  align-items: center;
  gap: 9px;
}

.timeline-entry p {
  margin: 10px 0 0;
  color: #34413d;
  font-size: 13px;
}

.timeline-entry small {
  display: block;
  margin-top: 6px;
  color: #7e8985;
  line-height: 1.5;
}

@media (max-width: 900px) {
  .customer-workspace {
    padding: 20px 16px 28px;
  }

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

@media (max-width: 760px) {
  .desktop-user-table {
    display: none;
  }

  .mobile-user-list {
    display: block;
    min-height: 160px;
  }

  .mobile-user-item {
    padding: 16px;
    border-bottom: 1px solid #e8eeec;
    background: #ffffff;
  }

  .mobile-user-item:last-child {
    border-bottom: 0;
  }

  .mobile-user-item header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }

  .mobile-user-item .identity-cell {
    min-width: 0;
    flex: 1;
  }

  .mobile-user-metrics {
    display: grid;
    grid-template-columns: 0.8fr 0.8fr 1.4fr;
    gap: 8px;
    margin-top: 15px;
    padding: 12px;
    border-radius: 7px;
    background: #f5f8f7;
  }

  .mobile-user-metrics span,
  .mobile-user-metrics strong {
    display: block;
    min-width: 0;
  }

  .mobile-user-metrics span {
    color: #87918e;
    font-size: 10px;
  }

  .mobile-user-metrics strong {
    overflow: hidden;
    margin-bottom: 4px;
    color: #2b3834;
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .mobile-detail-link {
    display: flex;
    width: 100%;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    margin-top: 10px;
    padding: 8px 0 0;
    border: 0;
    background: transparent;
    color: #16766e;
    font-size: 12px;
    font-weight: 600;
    cursor: pointer;
  }
}

@media (max-width: 560px) {
  .workspace-header h1 {
    font-size: 23px;
  }

  .directory-search {
    align-items: stretch;
    flex-wrap: wrap;
  }

  .directory-search .el-input {
    flex-basis: 100%;
  }

  .detail-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .profile-list div {
    grid-template-columns: 88px minmax(0, 1fr);
  }
}
</style>
