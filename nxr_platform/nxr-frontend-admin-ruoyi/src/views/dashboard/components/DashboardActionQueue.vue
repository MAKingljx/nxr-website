<template>
  <section class="dashboard-panel queue-panel">
    <header class="panel-header">
      <div>
        <p class="panel-kicker">{{ $tx('ACTION QUEUE') }}</p>
        <h2>{{ $tx('Items Requiring Attention') }}</h2>
      </div>
      <span class="panel-meta">{{ rows.length }} {{ $tx('open') }}</span>
    </header>

    <el-table v-loading="loading" :data="rows" class="queue-table" height="342">
      <el-table-column :label="$tx('Type')" width="118">
        <template #default="scope">
          <el-tag effect="plain" :type="kindTone(scope.row.kind)">{{ kindLabel(scope.row.kind) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="$tx('Reference')" min-width="225" show-overflow-tooltip>
        <template #default="scope">
          <strong class="queue-reference">{{ scope.row.reference }}</strong>
          <span v-if="scope.row.title" class="queue-title">{{ scope.row.title }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="$tx('Status')" min-width="150" show-overflow-tooltip>
        <template #default="scope">{{ statusLabel(scope.row.statusCode) }}</template>
      </el-table-column>
      <el-table-column :label="$tx('Waiting')" width="125">
        <template #default="scope">
          <span :title="formatExactTime(scope.row.actionAt)">{{ formatWaiting(scope.row.actionAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="$tx('Actions')" width="82" align="right">
        <template #default="scope">
          <el-button link type="primary" @click="$emit('navigate', scope.row.targetPath)">{{ $tx('Open') }}</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :description="$tx('No operational items require attention')" :image-size="64" />
      </template>
    </el-table>
  </section>
</template>

<script setup>
import { useI18n } from 'vue-i18n'

const props = defineProps({
  rows: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
})

defineEmits(['navigate'])

const { locale } = useI18n()

const kindLabels = {
  review: 'Review',
  publication: 'Publication',
  media: 'Images',
  payment: 'Payment',
  order: 'Order'
}

const statusLabels = {
  pending: 'Pending Review',
  review: 'In Review',
  ready_to_publish: 'Ready to Publish',
  missing_images: 'Missing Images',
  awaiting_payment: 'Awaiting Payment',
  payment_review: 'Payment Review',
  awaiting_inbound: 'Awaiting Cards',
  inbound_shipped: 'Shipped to NXR',
  intake_exception: 'Intake Exception',
  received: 'Cards Received',
  grading: 'Grading',
  quality_check: 'Quality Check',
  quality_hold: 'QC Rework',
  completed: 'Ready to Return',
  return_shipped: 'Return Shipped'
}

function kindLabel(kind) {
  return tx(kindLabels[kind] || 'Task')
}

function kindTone(kind) {
  if (kind === 'media') return 'danger'
  if (kind === 'payment') return 'warning'
  if (kind === 'publication') return 'success'
  return 'primary'
}

function statusLabel(status) {
  const source = statusLabels[status]
  if (source) return tx(source)
  return String(status || '-').replaceAll('_', ' ')
}

function parseDate(value) {
  if (!value) return null
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

function formatWaiting(value) {
  const parsed = parseDate(value)
  if (!parsed) return '-'
  const delta = parsed.getTime() - Date.now()
  const absolute = Math.abs(delta)
  const relative = new Intl.RelativeTimeFormat(locale.value, { numeric: 'auto' })
  if (absolute >= 86400000) return relative.format(Math.round(delta / 86400000), 'day')
  if (absolute >= 3600000) return relative.format(Math.round(delta / 3600000), 'hour')
  return relative.format(Math.round(delta / 60000), 'minute')
}

function formatExactTime(value) {
  const parsed = parseDate(value)
  if (!parsed) return ''
  return new Intl.DateTimeFormat(locale.value, {
    year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
  }).format(parsed)
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

.panel-meta {
  color: var(--nxr-text-faint);
  font-size: 11px;
}

.queue-reference,
.queue-title {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.queue-reference {
  color: var(--nxr-text);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}

.queue-title {
  margin-top: 3px;
  color: var(--nxr-text-faint);
  font-size: 11px;
}

:deep(.el-table__inner-wrapper::before) {
  display: none;
}

:deep(.el-table th.el-table__cell) {
  height: 42px;
  background: var(--nxr-surface-subtle);
  color: var(--nxr-text-muted);
  font-size: 12px;
}

:deep(.el-table td.el-table__cell) {
  height: 54px;
  border-bottom-color: var(--nxr-border-subtle);
}
</style>
