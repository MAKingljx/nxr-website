<script setup lang="ts">
import { computed, useId } from 'vue'
import {
  PhoenixBarChart,
  PhoenixChartLegend,
  PhoenixDashboardFilter,
  PhoenixMetricCard,
  PhoenixRankingList,
  PhoenixTrendChart,
} from '../../../primitives/analytics'
import type {
  PhoenixAnalyticsAppearance,
  PhoenixAnalyticsValueKind,
  PhoenixChartDatum,
  PhoenixDashboardFilterItem,
  PhoenixDashboardFilterValue,
  PhoenixLegendItem,
  PhoenixRankingItem,
} from '../../../primitives/analytics'

export interface PhoenixAnalyticsDashboardMetric {
  key: string
  label: string
  value: number
  kind?: PhoenixAnalyticsValueKind
  currency?: string
  unit?: string
  description?: string
  trend?: number
  trendLabel?: string
  disabled?: boolean
}

export interface PhoenixAnalyticsDashboardSelection {
  metric?: string
  trend?: string
  category?: string
  ranking?: string
}

export type PhoenixAnalyticsDashboardSelectionSource = 'metric' | 'trend' | 'category' | 'ranking'

export interface PhoenixAnalyticsDashboardSelectEvent {
  source: PhoenixAnalyticsDashboardSelectionSource
  key: string
  item: PhoenixAnalyticsDashboardMetric | PhoenixChartDatum | PhoenixRankingItem
  position: number
}

export interface PhoenixAnalyticsDashboardToggleEvent {
  key: string
  visible: boolean
  hiddenKeys: string[]
  item: PhoenixLegendItem
}

const props = withDefaults(defineProps<{
  metrics: PhoenixAnalyticsDashboardMetric[]
  trend: PhoenixChartDatum[]
  categories: PhoenixChartDatum[]
  ranking: PhoenixRankingItem[]
  filters: PhoenixDashboardFilterItem[]
  values?: PhoenixDashboardFilterValue
  selection?: PhoenixAnalyticsDashboardSelection
  hiddenLegendKeys?: string[]
  title?: string
  subtitle?: string
  trendTitle?: string
  categoriesTitle?: string
  rankingTitle?: string
  locale?: string
  currency?: string
  appearance?: PhoenixAnalyticsAppearance
}>(), {
  values: () => ({}),
  selection: () => ({}),
  hiddenLegendKeys: () => [],
  title: '经营数据看板',
  subtitle: '集中查看关键指标、趋势表现与业务排行',
  trendTitle: '核心趋势',
  categoriesTitle: '分类表现',
  rankingTitle: '业务排行',
  locale: 'zh-CN',
  currency: 'CNY',
  appearance: 'modern',
})

const emit = defineEmits<{
  filter: [values: PhoenixDashboardFilterValue]
  select: [event: PhoenixAnalyticsDashboardSelectEvent]
  toggle: [event: PhoenixAnalyticsDashboardToggleEvent]
}>()

const headingId = `px-analytics-dashboard-${useId().replace(/[^a-z\d-_]/gi, '')}`

function uniqueByKey<T extends { key: string }>(items: T[]) {
  const seen = new Set<string>()
  return items.filter((item) => item.key.length > 0 && !seen.has(item.key) && Boolean(seen.add(item.key)))
}

const normalizedMetrics = computed(() => uniqueByKey(props.metrics))
const normalizedCategories = computed(() => uniqueByKey(props.categories))
const visibleCategories = computed(() => normalizedCategories.value.filter((item) => !props.hiddenLegendKeys.includes(item.key)))
const legendItems = computed<PhoenixLegendItem[]>(() => normalizedCategories.value.map((item) => ({
  key: item.key,
  label: item.label,
  color: item.color,
  value: item.value,
  disabled: item.disabled,
})))

function select(
  source: PhoenixAnalyticsDashboardSelectionSource,
  item: PhoenixAnalyticsDashboardMetric | PhoenixChartDatum | PhoenixRankingItem,
  position: number,
) {
  emit('select', { source, key: item.key, item, position })
}

function toggle(item: PhoenixLegendItem, visible: boolean) {
  const nextHidden = new Set(props.hiddenLegendKeys)
  if (visible) nextHidden.delete(item.key)
  else nextHidden.add(item.key)
  emit('toggle', { key: item.key, visible, hiddenKeys: [...nextHidden], item })
}
</script>

<template>
  <main class="px-analytics-dashboard-page" :class="`is-${appearance}`" :aria-labelledby="headingId">
    <header class="px-analytics-dashboard-page__hero">
      <div>
        <p>PHOENIX ANALYTICS</p>
        <h1 :id="headingId">{{ title }}</h1>
        <span>{{ subtitle }}</span>
      </div>
      <div v-if="$slots.actions" class="px-analytics-dashboard-page__actions"><slot name="actions"></slot></div>
    </header>

    <PhoenixDashboardFilter
      v-if="filters.length"
      class="px-analytics-dashboard-page__filters"
      :filters="filters"
      :model-value="values"
      :appearance="appearance"
      :show-submit="false"
      @update:model-value="emit('filter', $event)"
      @submit="emit('filter', $event)"
    />

    <section class="px-analytics-dashboard-page__metrics" aria-label="核心指标">
      <PhoenixMetricCard
        v-for="(metric, index) in normalizedMetrics"
        :key="metric.key"
        :label="metric.label"
        :value="metric.value"
        :kind="metric.kind"
        :currency="metric.currency || currency"
        :locale="locale"
        :unit="metric.unit"
        :description="metric.description"
        :trend="metric.trend"
        :trend-label="metric.trendLabel"
        :appearance="appearance"
        :interactive="true"
        :selected="selection.metric === metric.key"
        :disabled="metric.disabled"
        @select="select('metric', metric, index + 1)"
      />
      <p v-if="!normalizedMetrics.length" class="px-analytics-dashboard-page__empty" role="status">暂无核心指标</p>
    </section>

    <section class="px-analytics-dashboard-page__primary" aria-label="趋势与排行">
      <PhoenixTrendChart
        :data="trend"
        :title="trendTitle"
        :active-key="selection.trend"
        :locale="locale"
        :currency="currency"
        :appearance="appearance"
        @select="(item, index) => select('trend', item, index + 1)"
      />
      <PhoenixRankingList
        :items="ranking"
        :title="rankingTitle"
        :selected-key="selection.ranking"
        :locale="locale"
        :currency="currency"
        :appearance="appearance"
        @select="(item, rank) => select('ranking', item, rank)"
      />
    </section>

    <section class="px-analytics-dashboard-page__categories" :aria-label="categoriesTitle">
      <div class="px-analytics-dashboard-page__legend">
        <PhoenixChartLegend
          :items="legendItems"
          :hidden-keys="hiddenLegendKeys"
          :appearance="appearance"
          @toggle="toggle"
        />
      </div>
      <PhoenixBarChart
        :data="visibleCategories"
        :title="categoriesTitle"
        :active-key="selection.category"
        :locale="locale"
        :currency="currency"
        :appearance="appearance"
        @select="(item, index) => select('category', item, index + 1)"
      />
    </section>
  </main>
</template>

<style scoped>
.px-analytics-dashboard-page {
  --dashboard-canvas: #f4f6fb;
  --dashboard-surface: #fff;
  --dashboard-border: #dfe3ee;
  --dashboard-ink: #182033;
  --dashboard-muted: #6f7890;
  display: grid;
  box-sizing: border-box;
  gap: 18px;
  min-width: 0;
  padding: clamp(16px, 3vw, 32px);
  color: var(--px-ink, var(--dashboard-ink));
  background: var(--dashboard-canvas);
  font-family: var(--px-font, Inter, "PingFang SC", "Microsoft YaHei", ui-sans-serif, system-ui, sans-serif);
}

.px-analytics-dashboard-page.is-minimal { --dashboard-canvas: #fff; gap: 24px; }
.px-analytics-dashboard-page.is-soft { --dashboard-canvas: #eef1f8; }
.px-analytics-dashboard-page__hero { display: flex; min-width: 0; align-items: flex-end; justify-content: space-between; gap: 20px; }
.px-analytics-dashboard-page__hero p { margin: 0 0 6px; color: var(--px-primary, #5b5ce2); font-size: 10px; font-weight: 800; letter-spacing: .16em; }
.px-analytics-dashboard-page__hero h1 { margin: 0; font-size: clamp(24px, 4vw, 36px); letter-spacing: -.045em; line-height: 1.15; }
.px-analytics-dashboard-page__hero span { display: block; margin-top: 8px; color: var(--px-muted, var(--dashboard-muted)); font-size: 13px; line-height: 1.6; }
.px-analytics-dashboard-page__actions { display: flex; flex: 0 0 auto; align-items: center; gap: 8px; }
.px-analytics-dashboard-page__metrics { display: grid; min-width: 0; grid-template-columns: repeat(auto-fit, minmax(min(100%, 210px), 1fr)); gap: 12px; }
.px-analytics-dashboard-page__primary { display: grid; min-width: 0; grid-template-columns: minmax(0, 2fr) minmax(250px, .8fr); gap: 14px; }
.px-analytics-dashboard-page__categories { position: relative; min-width: 0; }
.px-analytics-dashboard-page__legend { position: absolute; z-index: 1; inset: 11px 16px auto auto; max-width: 52%; }
.px-analytics-dashboard-page__empty { display: grid; min-height: 140px; grid-column: 1 / -1; margin: 0; place-items: center; border: 1px dashed var(--dashboard-border); border-radius: 14px; color: var(--dashboard-muted); background: var(--dashboard-surface); font-size: 13px; }

@media (max-width: 900px) {
  .px-analytics-dashboard-page__primary { grid-template-columns: 1fr; }
  .px-analytics-dashboard-page__legend { position: static; max-width: none; margin-bottom: 8px; }
}

@media (max-width: 560px) {
  .px-analytics-dashboard-page { gap: 14px; padding: 12px; }
  .px-analytics-dashboard-page__hero { align-items: stretch; flex-direction: column; }
  .px-analytics-dashboard-page__actions { justify-content: flex-start; }
  .px-analytics-dashboard-page__metrics { grid-template-columns: 1fr; }
}
</style>
