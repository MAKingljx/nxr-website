<script setup lang="ts">
import { ref } from 'vue'
import {
  PhoenixBarChart,
  PhoenixChartLegend,
  PhoenixDashboardFilter,
  PhoenixDataSummary,
  PhoenixDonutChart,
  PhoenixFunnelChart,
  PhoenixLineChart,
  PhoenixMetricCard,
  PhoenixRankingList,
  PhoenixTrendChart,
} from '../primitives/analytics'

const activeKey = ref('july')
const selectedKey = ref('mall')
const hiddenKeys = ref<string[]>([])
const filters = ref({ period: 'month', region: 'all' })
const trendData = [
  { key: 'may', label: '五月', value: 860 },
  { key: 'june', label: '六月', value: 1120 },
  { key: 'july', label: '七月', value: 1286 },
  { key: 'august', label: '八月', value: 1560 },
]
const categoryData = [
  { key: 'mall', label: '商城', value: 1560, color: '#635bff' },
  { key: 'booking', label: '预约', value: 980, color: '#16a979' },
  { key: 'learning', label: '教学', value: 760, color: '#f59e0b' },
  { key: 'community', label: '社区', value: 520, color: '#e85d8a' },
]
const series = [
  { key: 'visitors', label: '访问人数', color: '#635bff', points: trendData },
  { key: 'orders', label: '业务订单', color: '#16a979', points: trendData.map((item, index) => ({ ...item, value: [420, 580, 690, 860][index] })) },
]
</script>

<template>
  <div class="cg-analytics-showcase">
    <div class="cg-analytics-metrics">
      <PhoenixMetricCard label="今日访问" :value="1286" :trend="12.6" appearance="modern" />
      <PhoenixMetricCard label="成交金额" :value="26890" kind="currency" :trend="8.2" appearance="soft" />
      <PhoenixMetricCard label="转化率" :value="6.8" kind="percent" :trend="-1.4" appearance="minimal" />
    </div>
    <PhoenixDashboardFilter v-model="filters" :filters="[{ key: 'period', label: '周期', options: [{ label: '本月', value: 'month' }, { label: '本季度', value: 'quarter' }] }, { key: 'region', label: '区域', options: [{ label: '全部区域', value: 'all' }, { label: '华北', value: 'north' }, { label: '华东', value: 'east' }] }]" />
    <div class="cg-analytics-grid">
      <PhoenixTrendChart v-model:active-key="activeKey" :data="trendData" title="业务增长趋势" />
      <PhoenixBarChart v-model:active-key="selectedKey" :data="categoryData" title="场景业务量" appearance="soft" />
      <PhoenixLineChart :series="series" :hidden-keys="hiddenKeys" title="访问与订单趋势" />
      <PhoenixDonutChart v-model:selected-key="selectedKey" :data="categoryData" title="业务场景占比" appearance="minimal" />
      <PhoenixFunnelChart :stages="[{ key: 'visit', label: '访问', value: 12860 }, { key: 'interest', label: '浏览详情', value: 8260 }, { key: 'intent', label: '提交意向', value: 3280 }, { key: 'complete', label: '完成业务', value: 1560 }]" />
      <PhoenixRankingList v-model:selected-key="selectedKey" :items="categoryData.map((item, index) => ({ ...item, trend: [12.6, 8.2, 5.4, -1.2][index] }))" title="热门业务排行" appearance="soft" />
    </div>
    <div class="cg-analytics-footer">
      <PhoenixDataSummary :items="[{ key: 'users', label: '活跃用户', value: 8650, note: '近 30 天' }, { key: 'orders', label: '完成订单', value: 1560, note: '本月累计' }, { key: 'refund', label: '退款率', value: 1.2, kind: 'percent', note: '低于目标' }]" />
      <PhoenixChartLegend v-model:hidden-keys="hiddenKeys" :items="series.map((item) => ({ key: item.key, label: item.label, color: item.color }))" />
    </div>
  </div>
</template>
