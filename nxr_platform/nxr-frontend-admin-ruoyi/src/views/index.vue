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
          :detail="productMixSummary"
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

      <section class="chart-grid" :aria-label="$tx('Operations analytics')">
        <dashboard-chart
          :kicker="$tx('ACTIVITY')"
          :title="$tx('Entry & Publication Trend')"
          :meta="$tx('Last 30 days')"
          :option="trendChartOption"
          :empty="trendEmpty"
          :empty-text="$tx('No entry or publication activity in the last 30 days')"
        />
        <dashboard-chart
          :kicker="$tx('PRODUCTS')"
          :title="$tx('Product Mix')"
          :meta="$tx('{count} entries', { count: formatNumber(dashboard.totalSubmissions) })"
          :option="productChartOption"
          :empty="productEmpty"
          :empty-text="$tx('No product data yet')"
        />
        <dashboard-chart
          :kicker="$tx('FULFILLMENT')"
          :title="$tx('Order Pipeline')"
          :meta="$tx('{count} orders', { count: formatNumber(orderTotal) })"
          :option="orderChartOption"
          :empty="orderEmpty"
          :empty-text="$tx('No grading orders yet')"
        />
        <dashboard-chart
          :kicker="$tx('MEDIA')"
          :title="$tx('Image Publication Status')"
          :meta="$tx('{count} tracked', { count: formatNumber(dashboard.mediaStatus.tracked) })"
          :option="mediaChartOption"
          :empty="mediaEmpty"
          :empty-text="$tx('No approved media records yet')"
        />
      </section>

      <dashboard-action-queue
        class="dashboard-queue"
        :rows="dashboard.actionItems"
        :loading="loading"
        @navigate="navigate"
      />

      <section class="dashboard-content">
        <dashboard-recent
          :entries="dashboard.recentEntries"
          :orders="dashboard.recentOrders"
          :published="dashboard.recentPublished"
          :loading="loading"
          @navigate="navigate"
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
import { useI18n } from 'vue-i18n'
import { fetchDashboard } from '@/api/nxr/entries'
import auth from '@/plugins/auth'
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import DashboardActionQueue from './dashboard/components/DashboardActionQueue.vue'
import DashboardActionRail from './dashboard/components/DashboardActionRail.vue'
import DashboardChart from './dashboard/components/DashboardChart.vue'
import DashboardMetricCard from './dashboard/components/DashboardMetricCard.vue'
import DashboardRecent from './dashboard/components/DashboardRecent.vue'
import DashboardWorkflow from './dashboard/components/DashboardWorkflow.vue'

const router = useRouter()
const { locale } = useI18n()
const canViewDashboard = auth.hasPermi('nxr:dashboard:view')
const loading = ref(false)
const loadError = ref(false)
const updatedAt = ref('--:--')

const chartColors = {
  primary: '#4a7fa8',
  green: '#4f8a68',
  amber: '#c0813e',
  red: '#c45d5d',
  purple: '#7668a8',
  slate: '#778681',
  text: '#7e8985',
  grid: 'rgba(126, 137, 133, 0.18)'
}

function emptyDashboard() {
  return {
    totalSubmissions: 0,
    pendingReview: 0,
    approvedReady: 0,
    publishedCertificates: 0,
    waitlistCount: 0,
    activityTrend: [],
    productMix: [],
    orderPipeline: [],
    mediaStatus: { tracked: 0, missing: 0, ready: 0, published: 0 },
    actionItems: [],
    recentEntries: [],
    recentOrders: [],
    recentPublished: []
  }
}

const dashboard = ref(emptyDashboard())
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
const todayLabel = computed(() => new Intl.DateTimeFormat(locale.value, {
  month: 'long', day: 'numeric', weekday: 'short'
}).format(new Date()))
const productCounts = computed(() => Object.fromEntries(
  (dashboard.value.productMix || []).map((item) => [item.code, Number(item.count) || 0])
))
const productMixSummary = computed(() => tx('{graded} graded · {merch} merch · {vintage} vintage', {
  graded: formatNumber(productCounts.value.graded_card),
  merch: formatNumber(productCounts.value.merch_product),
  vintage: formatNumber(productCounts.value.vintage_product)
}))
const trendEmpty = computed(() => (dashboard.value.activityTrend || []).every((item) => !item.created && !item.published))
const productEmpty = computed(() => !dashboard.value.totalSubmissions)
const orderTotal = computed(() => (dashboard.value.orderPipeline || []).reduce((sum, item) => sum + (Number(item.orders) || 0), 0))
const orderEmpty = computed(() => orderTotal.value === 0)
const mediaEmpty = computed(() => !dashboard.value.mediaStatus?.tracked)

const trendChartOption = computed(() => {
  const rows = dashboard.value.activityTrend || []
  return {
    animationDuration: 450,
    color: [chartColors.primary, chartColors.green],
    tooltip: { trigger: 'axis' },
    legend: { bottom: 10, textStyle: { color: chartColors.text, fontSize: 11 } },
    grid: { left: 46, right: 22, top: 28, bottom: 56 },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: rows.map((item) => formatChartDate(item.date)),
      axisLine: { lineStyle: { color: chartColors.grid } },
      axisTick: { show: false },
      axisLabel: { color: chartColors.text, fontSize: 10, interval: 4 }
    },
    yAxis: {
      type: 'value', minInterval: 1,
      axisLabel: { color: chartColors.text, fontSize: 10 },
      splitLine: { lineStyle: { color: chartColors.grid } }
    },
    series: [
      {
        name: tx('New Entries'), type: 'line', smooth: true, symbol: 'none',
        lineStyle: { width: 2.5 }, areaStyle: { opacity: 0.1 },
        data: rows.map((item) => item.created)
      },
      {
        name: tx('Published'), type: 'line', smooth: true, symbol: 'none',
        lineStyle: { width: 2.5 }, areaStyle: { opacity: 0.06 },
        data: rows.map((item) => item.published)
      }
    ]
  }
})

const productChartOption = computed(() => {
  const data = [
    { name: tx('Graded Card'), value: productCounts.value.graded_card || 0, itemStyle: { color: chartColors.primary } },
    { name: tx('Merch Product'), value: productCounts.value.merch_product || 0, itemStyle: { color: chartColors.amber } },
    { name: tx('Vintage Card'), value: productCounts.value.vintage_product || 0, itemStyle: { color: chartColors.purple } }
  ]
  return {
    animationDuration: 450,
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 8, left: 'center', textStyle: { color: chartColors.text, fontSize: 10 } },
    series: [{
      type: 'pie', radius: ['48%', '70%'], center: ['50%', '43%'], avoidLabelOverlap: true,
      itemStyle: { borderColor: 'transparent', borderWidth: 3 },
      label: { formatter: '{b}\n{c}', color: chartColors.text, fontSize: 10, lineHeight: 15 },
      labelLine: { length: 8, length2: 7 },
      data
    }]
  }
})

const orderChartOption = computed(() => {
  const rows = dashboard.value.orderPipeline || []
  return {
    animationDuration: 450,
    color: [chartColors.primary, chartColors.amber],
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { bottom: 8, textStyle: { color: chartColors.text, fontSize: 11 } },
    grid: { left: 96, right: 24, top: 20, bottom: 54 },
    xAxis: {
      type: 'value', minInterval: 1,
      axisLabel: { color: chartColors.text, fontSize: 10 },
      splitLine: { lineStyle: { color: chartColors.grid } }
    },
    yAxis: {
      type: 'category',
      data: rows.map((item) => orderStageLabel(item.code)),
      axisLine: { show: false }, axisTick: { show: false },
      axisLabel: { color: chartColors.text, fontSize: 10 }
    },
    series: [
      { name: tx('Orders'), type: 'bar', barMaxWidth: 13, data: rows.map((item) => item.orders) },
      { name: tx('Cards'), type: 'bar', barMaxWidth: 13, data: rows.map((item) => item.cards) }
    ]
  }
})

const mediaChartOption = computed(() => {
  const media = dashboard.value.mediaStatus || emptyDashboard().mediaStatus
  const rows = [
    { label: tx('Missing Images'), value: media.missing, color: chartColors.red },
    { label: tx('Ready to Publish'), value: media.ready, color: chartColors.amber },
    { label: tx('Published'), value: media.published, color: chartColors.green }
  ]
  return {
    animationDuration: 450,
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 110, right: 30, top: 24, bottom: 28 },
    xAxis: {
      type: 'value', minInterval: 1,
      axisLabel: { color: chartColors.text, fontSize: 10 },
      splitLine: { lineStyle: { color: chartColors.grid } }
    },
    yAxis: {
      type: 'category', data: rows.map((item) => item.label),
      axisLine: { show: false }, axisTick: { show: false },
      axisLabel: { color: chartColors.text, fontSize: 10 }
    },
    series: [{
      type: 'bar', barWidth: 24,
      label: { show: true, position: 'right', color: chartColors.text, fontWeight: 700 },
      data: rows.map((item) => ({ value: item.value, itemStyle: { color: item.color, borderRadius: [0, 5, 5, 0] } }))
    }]
  }
})

function formatNumber(value) {
  return new Intl.NumberFormat(locale.value).format(Number(value) || 0)
}

function formatChartDate(value) {
  if (!value) return ''
  const [year, month, day] = String(value).split('-').map(Number)
  const parsed = new Date(year, month - 1, day)
  return new Intl.DateTimeFormat(locale.value, { month: 'short', day: 'numeric' }).format(parsed)
}

function orderStageLabel(code) {
  return tx({
    payment: 'Payment', inbound: 'Inbound', grading: 'Grading', return: 'Return Shipping',
    completed: 'Completed', cancelled: 'Cancelled'
  }[code] || code)
}

function navigate(path) {
  router.push(path)
}

async function loadDashboard() {
  loading.value = true
  loadError.value = false
  try {
    const response = await fetchDashboard()
    const incoming = response.data || {}
    dashboard.value = {
      ...emptyDashboard(),
      ...incoming,
      mediaStatus: { ...emptyDashboard().mediaStatus, ...(incoming.mediaStatus || {}) }
    }
    updatedAt.value = new Intl.DateTimeFormat(locale.value, {
      hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
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

.chart-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) minmax(310px, 0.9fr);
  gap: 16px;
  margin-top: 16px;
}

.dashboard-queue {
  margin-top: 16px;
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

@media (max-width: 1000px) {
  .chart-grid,
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
