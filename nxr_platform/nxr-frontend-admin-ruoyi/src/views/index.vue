<template>
  <main class="nxr-dashboard">
    <header class="dashboard-header">
      <div>
        <p class="dashboard-header__kicker">NXR OPERATIONS</p>
        <h1>运营总览</h1>
        <p class="dashboard-header__summary">{{ workloadSummary }}</p>
      </div>
      <div class="dashboard-header__actions">
        <span class="dashboard-date">{{ todayLabel }}</span>
        <el-tooltip content="刷新数据" placement="bottom">
          <el-button :icon="Refresh" circle :loading="loading" aria-label="刷新数据" @click="loadDashboard" />
        </el-tooltip>
      </div>
    </header>

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
        tone="blue"
        :icon="Files"
      />
      <dashboard-metric-card
        label="待审批"
        :value="dashboard.pendingReview"
        detail="需要评级人员处理"
        tone="amber"
        :icon="DocumentChecked"
      />
      <dashboard-metric-card
        label="待发布"
        :value="dashboard.approvedReady"
        detail="已审批，等待证书发布"
        tone="teal"
        :icon="UploadFilled"
      />
      <dashboard-metric-card
        label="已发布证书"
        :value="dashboard.publishedCertificates"
        :detail="`发布率 ${publishedRate}`"
        tone="green"
        :icon="Medal"
      />
    </section>

    <section class="workflow-strip" aria-label="评级处理进度">
      <div class="workflow-strip__heading">
        <span>评级流程</span>
        <strong>{{ pendingWork }} 项待处理</strong>
      </div>
      <div class="workflow-step">
        <span class="workflow-step__number">01</span>
        <div><strong>资料录入</strong><small>{{ formatNumber(dashboard.totalSubmissions) }} 条</small></div>
      </div>
      <el-icon class="workflow-arrow"><ArrowRight /></el-icon>
      <div class="workflow-step workflow-step--attention">
        <span class="workflow-step__number">02</span>
        <div><strong>审批处理</strong><small>{{ formatNumber(dashboard.pendingReview) }} 条</small></div>
      </div>
      <el-icon class="workflow-arrow"><ArrowRight /></el-icon>
      <div class="workflow-step workflow-step--ready">
        <span class="workflow-step__number">03</span>
        <div><strong>证书发布</strong><small>{{ formatNumber(dashboard.approvedReady) }} 条待发布</small></div>
      </div>
    </section>

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
import DashboardActionRail from './dashboard/components/DashboardActionRail.vue'
import DashboardMetricCard from './dashboard/components/DashboardMetricCard.vue'
import DashboardRecent from './dashboard/components/DashboardRecent.vue'

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
  min-height: 100%;
  padding: 26px 28px 34px;
  background: #f4f7f6;
  color: #17221f;
}

.dashboard-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 22px;
}

.dashboard-header__kicker {
  margin: 0 0 6px;
  color: #16766e;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0;
}

.dashboard-header h1 {
  margin: 0;
  color: #15201d;
  font-size: 28px;
  line-height: 1.2;
  letter-spacing: 0;
}

.dashboard-header__summary {
  margin: 8px 0 0;
  color: #6f7a76;
  font-size: 13px;
}

.dashboard-header__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.dashboard-date {
  color: #68736f;
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

.workflow-strip {
  display: grid;
  min-height: 92px;
  grid-template-columns: minmax(170px, 0.9fr) minmax(150px, 1fr) 20px minmax(150px, 1fr) 20px minmax(170px, 1fr);
  align-items: center;
  gap: 12px;
  margin-top: 16px;
  padding: 15px 20px;
  border: 1px solid #dde5e2;
  border-radius: 8px;
  background: #ffffff;
}

.workflow-strip__heading span,
.workflow-strip__heading strong {
  display: block;
}

.workflow-strip__heading span {
  color: #7c8783;
  font-size: 12px;
}

.workflow-strip__heading strong {
  margin-top: 6px;
  color: #26332f;
  font-size: 16px;
}

.workflow-step {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 11px;
  padding: 10px 12px;
  border-left: 3px solid #67879a;
  background: #f5f8fa;
}

.workflow-step--attention {
  border-left-color: #b66a28;
  background: #fff7ee;
}

.workflow-step--ready {
  border-left-color: #16766e;
  background: #eef8f5;
}

.workflow-step__number {
  color: #85908c;
  font-size: 11px;
  font-weight: 800;
}

.workflow-step div {
  min-width: 0;
}

.workflow-step strong,
.workflow-step small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-step strong {
  color: #25312e;
  font-size: 13px;
}

.workflow-step small {
  margin-top: 4px;
  color: #7e8985;
  font-size: 11px;
}

.workflow-arrow {
  color: #aeb7b4;
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
  color: #8a9491;
  font-size: 11px;
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #4b8962;
}

.status-dot--error {
  background: #bb4e4e;
}

@media (max-width: 1280px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .workflow-strip {
    grid-template-columns: minmax(150px, 0.8fr) repeat(3, minmax(145px, 1fr));
  }

  .workflow-arrow {
    display: none;
  }
}

@media (max-width: 900px) {
  .nxr-dashboard {
    padding: 20px 16px 28px;
  }

  .workflow-strip {
    grid-template-columns: 1fr;
  }

  .workflow-strip__heading {
    padding-bottom: 10px;
    border-bottom: 1px solid #edf1f0;
  }

  .dashboard-content {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 560px) {
  .dashboard-header {
    align-items: flex-end;
  }

  .dashboard-header h1 {
    font-size: 23px;
  }

  .dashboard-date {
    display: none;
  }

  .metric-grid {
    grid-template-columns: 1fr;
  }
}
</style>
