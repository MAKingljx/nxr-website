<template>
  <section class="dashboard-panel recent-panel">
    <header class="panel-header">
      <div>
        <p class="panel-kicker">{{ $tx('CERTIFICATES') }}</p>
        <h2>{{ $tx('Recently Published') }}</h2>
      </div>
      <el-button link type="primary" @click="$emit('view-all')"> {{ $tx('View All') }} <el-icon class="el-icon--right"><ArrowRight /></el-icon>
      </el-button>
    </header>

    <el-table v-loading="loading" :data="rows" class="recent-table" height="330">
      <el-table-column :label="$tx('Certificate')" min-width="150">
        <template #default="scope">
          <span class="cert-id">{{ scope.row.certId }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="$tx('Card')" min-width="240" show-overflow-tooltip>
        <template #default="scope">
          <strong class="card-name">{{ scope.row.cardName }}</strong>
          <span class="card-brand">{{ scope.row.brandName || $tx('Brand not set') }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="$tx('Result')" width="180" align="right">
        <template #default="scope">
          <span class="grade-badge">
            <strong>{{ resultPrimary(scope.row) }}</strong>
            <small>{{ resultSecondary(scope.row) }}</small>
          </span>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :description="$tx('No published certificates yet')" :image-size="72" />
      </template>
    </el-table>
  </section>
</template>

<script setup>
defineProps({
  rows: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
})

defineEmits(['view-all'])

function displayGrade(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number.toFixed(1) : '-'
}

function resultPrimary(row) {
  if (row.productType === 'merch_product' || row.productType === 'label_product') return tx('Merch')
  if (row.productType === 'vintage_product') return tx('Vintage')
  return displayGrade(row.finalGradeValue)
}

function resultSecondary(row) {
  if (row.productType === 'merch_product' || row.productType === 'label_product') return row.merchDescription || tx('Merch Product')
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
  min-height: 72px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--nxr-border-subtle);
}

.panel-kicker {
  margin: 0 0 4px;
  color: var(--nxr-accent);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0;
}

.panel-header h2 {
  margin: 0;
  color: var(--nxr-text-strong);
  font-size: 17px;
  line-height: 1.2;
}

.recent-table {
  width: 100%;
}

.cert-id {
  color: var(--nxr-accent);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  font-weight: 700;
}

.card-name,
.card-brand {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-name {
  color: var(--nxr-text);
  font-size: 13px;
}

.card-brand {
  margin-top: 3px;
  color: var(--nxr-text-faint);
  font-size: 11px;
}

.grade-badge {
  display: inline-flex;
  min-width: 102px;
  align-items: baseline;
  justify-content: flex-end;
  gap: 7px;
  color: var(--nxr-text-muted);
}

.grade-badge strong {
  color: var(--nxr-accent);
  font-size: 16px;
  font-variant-numeric: tabular-nums;
}

.grade-badge small {
  max-width: 70px;
  overflow: hidden;
  color: var(--nxr-text-faint);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
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
