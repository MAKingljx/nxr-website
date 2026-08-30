<template>
  <main class="nxr-workspace nxr-dashboard">
    <nxr-page-header :kicker="$tx('NXR OPERATIONS')" :title="$tx('Operations Dashboard')" :summary="workloadSummary">
      <template #actions>
        <span class="dashboard-date">{{ todayLabel }}</span>
        <el-tooltip v-if="canViewDashboard" :content="$tx('Refresh data')" placement="bottom">
          <el-button :icon="Refresh" circle :loading="loading" :aria-label="$tx('Refresh data')" @click="loadDashboard" />
        </el-tooltip>
      </template>
    </nxr-page-header>

    <template v-if="canViewDashboard">
      <el-alert
        v-if="loadError"
        class="dashboard-alert"
        :title="$tx('Operations data is temporarily unavailable')"
        type="error"
        :closable="false"
        show-icon
      >
        <template #default>
          <el-button link type="danger" @click="loadDashboard">{{ $tx('Reload') }}</el-button>
        </template>
      </el-alert>

      <section class="metric-grid" :aria-label="$tx('Core operations metrics')">
        <dashboard-metric-card
          :label="$tx('Total Entries')"
          :value="dashboard.totalSubmissions"
          :detail="$tx('All grading records')"
          :icon="Files"
        />
        <dashboard-metric-card
          :label="$tx('Pending Review')"
          :value="dashboard.pendingReview"
          :detail="$tx('Awaiting reviewer action')"
          :icon="DocumentChecked"
        />
        <dashboard-metric-card
          :label="$tx('Ready to Publish')"
          :value="dashboard.approvedReady"
          :detail="$tx('Approved certificates awaiting publication')"
          :icon="UploadFilled"
        />
        <dashboard-metric-card
          :label="$tx('Published Certificates')"
          :value="dashboard.publishedCertificates"
          :detail="$tx('Publication rate {rate}', { rate: publishedRate })"
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
          @view-all="navigate('/nxr/cards/entries')"
        />
        <dashboard-action-rail
          :waitlist-count="dashboard.waitlistCount"
          @navigate="navigate"
        />
      </section>

      <footer class="dashboard-footer">
        <span class="status-dot" :class="{ 'status-dot--error': loadError }"></span>
        {{ loadError ? $tx('Data connection error') : $tx('Data updated {time}', { time: updatedAt }) }}
      </footer>
    </template>

    <el-empty v-else :description="$tx('This account cannot view the dashboard. Use the navigation to open an available feature.')" />
  </main>
</template>

<script setup name="Index">
import { DocumentChecked, Files, Medal, Refresh, UploadFilled } from '@element-plus/icons-vue'
import { fetchDashboard } from '@/api/nxr/entries'
import auth from '@/plugins/auth'
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import DashboardActionRail from './dashboard/components/DashboardActionRail.vue'
import DashboardMetricCard from './dashboard/components/DashboardMetricCard.vue'
import DashboardRecent from './dashboard/components/DashboardRecent.vue'
import DashboardWorkflow from './dashboard/components/DashboardWorkflow.vue'

const router = useRouter()
const canViewDashboard = auth.hasPermi('nxr:dashboard:view')
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
  if (!canViewDashboard) return tx('Use the navigation to open a feature available to this account')
  if (loadError.value) return tx('Operations data failed to load. Try again shortly.')
  if (!pendingWork.value) return tx('No entries are waiting for review or publication')
  return tx('{pending} pending review · {ready} ready to publish', {
    pending: formatNumber(dashboard.value.pendingReview),
    ready: formatNumber(dashboard.value.approvedReady)
  })
})
const activeDocumentLocale = document.documentElement.lang || 'en'
const todayLabel = new Intl.DateTimeFormat(activeDocumentLocale, {
  month: 'long',
  day: 'numeric',
  weekday: 'short'
}).format(new Date())

function formatNumber(value) {
  return new Intl.NumberFormat(activeDocumentLocale).format(value || 0)
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
    updatedAt.value = new Intl.DateTimeFormat(activeDocumentLocale, {
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

if (canViewDashboard) loadDashboard()
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
