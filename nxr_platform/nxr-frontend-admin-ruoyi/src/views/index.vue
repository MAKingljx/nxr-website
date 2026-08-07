<template>
  <main class="nxr-workspace nxr-dashboard">
    <nxr-page-header kicker="NXR OPERATIONS" title="运营总览" :summary="workloadSummary">
      <template #actions>
        <span class="dashboard-date">{{ todayLabel }}</span>
        <el-tooltip content="刷新数据" placement="bottom">
          <el-button :icon="Refresh" circle :loading="loading" aria-label="刷新数据" @click="loadDashboard" />
        </el-tooltip>
      </template>
    </nxr-page-header>

    <el-alert
      v-if="loadError"
      class="dashboard-alert"
      title="暂时无法读取运营数据"
      type="error"
      :closable="false"
      show-icon
    >
      <template #default>
        <el-button link type="danger" @click="loadDashboard">重新加载</el-button>
      </template>
    </el-alert>

    <section class="metric-grid" aria-label="核心运营指标">
      <dashboard-metric-card
        label="总录入"
        :value="dashboard.totalSubmissions"
        detail="系统累计评级资料"
        :icon="Files"
      />
      <dashboard-metric-card
        label="待审批"
        :value="dashboard.pendingReview"
        detail="需要评级人员处理"
        :icon="DocumentChecked"
      />
      <dashboard-metric-card
        label="待发布"
        :value="dashboard.approvedReady"
        detail="已审批，等待证书发布"
        :icon="UploadFilled"
      />
      <dashboard-metric-card
        label="已发布证书"
        :value="dashboard.publishedCertificates"
        :detail="`发布率 ${publishedRate}`"
        :icon="Medal"
      />
    </section>

    <dashboard-workflow
      :total-submissions="dashboard.totalSubmissions"
      :pending-review="dashboard.pendingReview"
      :approved-ready="dashboard.approvedReady"
      :pending-work="pendingWork"
    />

    <section class="dashboard-content">
      <dashboard-recent
        :rows="dashboard.recentPublished"
        :loading="loading"
        @view-all="navigate('/nxr/entries')"
      />
      <dashboard-action-rail
        :waitlist-count="dashboard.waitlistCount"
        @navigate="navigate"
      />
    </section>

    <footer class="dashboard-footer">
      <span class="status-dot" :class="{ 'status-dot--error': loadError }"></span>
      {{ loadError ? '数据连接异常' : `数据已更新 ${updatedAt}` }}
    </footer>
  </main>
</template>

<script setup name="Index">
import { DocumentChecked, Files, Medal, Refresh, UploadFilled } from '@element-plus/icons-vue'
import { fetchDashboard } from '@/api/nxr/entries'
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import DashboardActionRail from './dashboard/components/DashboardActionRail.vue'
import DashboardMetricCard from './dashboard/components/DashboardMetricCard.vue'
import DashboardRecent from './dashboard/components/DashboardRecent.vue'
import DashboardWorkflow from './dashboard/components/DashboardWorkflow.vue'

const router = useRouter()
const loading = ref(false)
const loadError = ref(false)
const updatedAt = ref('--:--')
const dashboard = ref({
  totalSubmissions: 0,
  pendingReview: 0,
  approvedReady: 0,
  publishedCertificates: 0,
  waitlistCount: 0,
  recentPublished: []
})

const pendingWork = computed(() => dashboard.value.pendingReview + dashboard.value.approvedReady)
const publishedRate = computed(() => {
  if (!dashboard.value.totalSubmissions) return '0%'
  const rate = Math.min(100, (dashboard.value.publishedCertificates / dashboard.value.totalSubmissions) * 100)
  return `${rate.toFixed(1)}%`
})
const workloadSummary = computed(() => {
  if (loadError.value) return '运营数据加载失败，请稍后重试'
  if (!pendingWork.value) return '当前没有待审批或待发布项目'
  return `待审批 ${formatNumber(dashboard.value.pendingReview)} 项，待发布 ${formatNumber(dashboard.value.approvedReady)} 项`
})
const todayLabel = new Intl.DateTimeFormat('zh-CN', {
  month: 'long',
  day: 'numeric',
  weekday: 'short'
}).format(new Date())

function formatNumber(value) {
  return new Intl.NumberFormat('zh-CN').format(value || 0)
}

function navigate(path) {
  router.push(path)
}

async function loadDashboard() {
  loading.value = true
  loadError.value = false
  try {
    const response = await fetchDashboard()
    dashboard.value = { ...dashboard.value, ...response.data }
    updatedAt.value = new Intl.DateTimeFormat('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false
    }).format(new Date())
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

loadDashboard()
</script>

<style scoped lang="scss">
.nxr-dashboard {
  overflow-x: hidden;
}

.dashboard-date {
  color: var(--nxr-text-muted);
  font-size: 13px;
}

.dashboard-alert {
  margin-bottom: 18px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.dashboard-content {
  display: grid;
  grid-template-columns: minmax(0, 2.1fr) minmax(270px, 0.8fr);
  gap: 16px;
  margin-top: 16px;
}

.dashboard-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 7px;
  margin-top: 14px;
  color: var(--nxr-text-faint);
  font-size: 11px;
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--nxr-success);
}

.status-dot--error {
  background: var(--nxr-danger);
}

@media (max-width: 1280px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .dashboard-content {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 560px) {
  .dashboard-date {
    display: none;
  }

  .metric-grid {
    grid-template-columns: 1fr;
  }
}
</style>
