<template>
  <section class="dashboard-panel recent-panel">
    <header class="panel-header">
      <div>
        <p class="panel-kicker">{{ $tx('RECENT ACTIVITY') }}</p>
        <h2>{{ $tx('Recent Business') }}</h2>
      </div>
      <el-button link type="primary" @click="$emit('navigate', viewAllPath)">
        {{ $tx('View All') }} <el-icon class="el-icon--right"><ArrowRight /></el-icon>
      </el-button>
    </header>

    <el-tabs v-model="activeTab" class="activity-tabs">
      <el-tab-pane :label="$tx('Entries')" name="entries">
        <el-table v-loading="loading" :data="entries" class="recent-table" height="300">
          <el-table-column :label="$tx('Certificate')" width="130">
            <template #default="scope"><span class="reference-id">{{ scope.row.certId }}</span></template>
          </el-table-column>
          <el-table-column :label="$tx('Entry')" min-width="225" show-overflow-tooltip>
            <template #default="scope">
              <strong class="row-primary">{{ scope.row.cardName }}</strong>
              <span class="row-secondary">{{ productLabel(scope.row.productType) }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="$tx('Status')" width="120">
            <template #default="scope"><el-tag effect="plain" :type="statusTone(scope.row.statusCode)">{{ statusLabel(scope.row.statusCode) }}</el-tag></template>
          </el-table-column>
          <el-table-column :label="$tx('Created')" width="116" align="right">
            <template #default="scope">{{ formatDate(scope.row.createdAt) }}</template>
          </el-table-column>
          <template #empty><el-empty :description="$tx('No entries yet')" :image-size="64" /></template>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="$tx('Orders')" name="orders">
        <el-table v-loading="loading" :data="orders" class="recent-table" height="300">
          <el-table-column :label="$tx('Order No.')" min-width="180">
            <template #default="scope"><span class="reference-id">{{ scope.row.orderNo }}</span></template>
          </el-table-column>
          <el-table-column :label="$tx('Cards')" prop="cardCount" width="90" align="center" />
          <el-table-column :label="$tx('Status')" min-width="160">
            <template #default="scope"><el-tag effect="plain" :type="statusTone(scope.row.statusCode)">{{ statusLabel(scope.row.statusCode) }}</el-tag></template>
          </el-table-column>
          <el-table-column :label="$tx('Created')" width="116" align="right">
            <template #default="scope">{{ formatDate(scope.row.createdAt) }}</template>
          </el-table-column>
          <template #empty><el-empty :description="$tx('No orders yet')" :image-size="64" /></template>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="$tx('Published')" name="published">
        <el-table v-loading="loading" :data="published" class="recent-table" height="300">
          <el-table-column :label="$tx('Certificate')" width="130">
            <template #default="scope"><span class="reference-id">{{ scope.row.certId }}</span></template>
          </el-table-column>
          <el-table-column :label="$tx('Card')" min-width="220" show-overflow-tooltip>
            <template #default="scope">
              <strong class="row-primary">{{ scope.row.cardName }}</strong>
              <span class="row-secondary">{{ scope.row.brandName || $tx('Brand not set') }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="$tx('Result')" min-width="135" align="right">
            <template #default="scope">
              <span class="grade-badge"><strong>{{ resultPrimary(scope.row) }}</strong><small>{{ resultSecondary(scope.row) }}</small></span>
            </template>
          </el-table-column>
          <el-table-column :label="$tx('Published')" width="116" align="right">
            <template #default="scope">{{ formatDate(scope.row.publishedAt) }}</template>
          </el-table-column>
          <template #empty><el-empty :description="$tx('No published certificates yet')" :image-size="64" /></template>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </section>
</template>

<script setup>
import { ArrowRight } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'

defineProps({
  entries: { type: Array, default: () => [] },
  orders: { type: Array, default: () => [] },
  published: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
})

defineEmits(['navigate'])

const { locale } = useI18n()
const activeTab = ref('entries')
const viewAllPath = computed(() => ({
  entries: '/nxr/cards/entries',
  orders: '/nxr/submissions/orders',
  published: '/nxr/cards/entries?status=published'
}[activeTab.value]))

const statusLabels = {
  pending: 'Pending', review: 'In Review', approved: 'Approved', published: 'Published',
  awaiting_payment: 'Awaiting Payment', payment_review: 'Payment Review', awaiting_inbound: 'Awaiting Cards',
  inbound_shipped: 'Shipped to NXR', intake_exception: 'Intake Exception', received: 'Cards Received',
  grading: 'Grading', quality_check: 'Quality Check', quality_hold: 'QC Rework', completed: 'Ready to Return',
  return_shipped: 'Return Shipped', delivered: 'Delivered', cancelled: 'Cancelled'
}

function statusLabel(status) {
  return tx(statusLabels[status] || String(status || '-').replaceAll('_', ' '))
}

function statusTone(status) {
  if (['approved', 'published', 'completed', 'delivered'].includes(status)) return 'success'
  if (['pending', 'review', 'payment_review', 'quality_check'].includes(status)) return 'warning'
  if (['cancelled', 'intake_exception', 'quality_hold'].includes(status)) return 'danger'
  return 'info'
}

function productLabel(productType) {
  if (productType === 'merch_product') return tx('Merch Product')
  if (productType === 'vintage_product') return tx('Vintage Card')
  return tx('Graded Card')
}

function formatDate(value) {
  if (!value) return '-'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return '-'
  return new Intl.DateTimeFormat(locale.value, { month: 'short', day: 'numeric' }).format(parsed)
}

function displayGrade(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number.toFixed(1) : '-'
}

function resultPrimary(row) {
  if (row.productType === 'merch_product') return tx('Merch')
  if (row.productType === 'vintage_product') return tx('Vintage')
  return displayGrade(row.finalGradeValue)
}

function resultSecondary(row) {
  if (row.productType === 'merch_product') return row.merchDescription || tx('Merch Product')
  if (row.productType === 'vintage_product') return row.vintageClassification || tx('Vintage Card')
  return row.finalGradeLabel || ''
}
</script>

<style scoped>
.dashboard-panel {
  min-width: 0;
  border: 1px solid var(--nxr-border);
  border-radius: 8px;
  background: var(--nxr-surface);
}

.panel-header {
  display: flex;
  min-height: 70px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 15px 20px;
  border-bottom: 1px solid var(--nxr-border-subtle);
}

.panel-kicker {
  margin: 0 0 4px;
  color: var(--nxr-accent);
  font-size: 10px;
  font-weight: 800;
}

.panel-header h2 {
  margin: 0;
  color: var(--nxr-text-strong);
  font-size: 17px;
}

.activity-tabs {
  padding: 0 18px 12px;
}

.activity-tabs :deep(.el-tabs__header) {
  margin: 0;
}

.activity-tabs :deep(.el-tabs__item) {
  height: 48px;
  color: var(--nxr-text-muted);
  font-size: 12px;
}

.activity-tabs :deep(.el-tabs__item.is-active) {
  color: var(--nxr-accent);
}

.reference-id {
  color: var(--nxr-accent);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  font-weight: 700;
}

.row-primary,
.row-secondary {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-primary {
  color: var(--nxr-text);
  font-size: 13px;
}

.row-secondary {
  margin-top: 3px;
  color: var(--nxr-text-faint);
  font-size: 11px;
}

.grade-badge {
  display: inline-flex;
  align-items: baseline;
  justify-content: flex-end;
  gap: 6px;
}

.grade-badge strong {
  color: var(--nxr-accent);
  font-size: 15px;
}

.grade-badge small {
  max-width: 65px;
  overflow: hidden;
  color: var(--nxr-text-faint);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.el-table__inner-wrapper::before) {
  display: none;
}

:deep(.el-table th.el-table__cell) {
  height: 40px;
  background: var(--nxr-surface-subtle);
  color: var(--nxr-text-muted);
  font-size: 12px;
}

:deep(.el-table td.el-table__cell) {
  height: 52px;
  border-bottom-color: var(--nxr-border-subtle);
}
</style>
