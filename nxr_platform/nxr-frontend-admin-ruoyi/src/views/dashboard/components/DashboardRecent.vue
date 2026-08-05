<template>
  <section class="dashboard-panel recent-panel">
    <header class="panel-header">
      <div>
        <p class="panel-kicker">CERTIFICATES</p>
        <h2>最近发布</h2>
      </div>
      <el-button link type="primary" @click="$emit('view-all')">
        查看全部
        <el-icon class="el-icon--right"><ArrowRight /></el-icon>
      </el-button>
    </header>

    <el-table v-loading="loading" :data="rows" class="recent-table" height="330">
      <el-table-column label="证书" min-width="150">
        <template #default="scope">
          <span class="cert-id">{{ scope.row.certId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="卡片" min-width="240" show-overflow-tooltip>
        <template #default="scope">
          <strong class="card-name">{{ scope.row.cardName }}</strong>
          <span class="card-brand">{{ scope.row.brandName || '未设置品牌' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="评级" width="150" align="right">
        <template #default="scope">
          <span class="grade-badge" :class="gradeClass(scope.row.finalGradeValue)">
            <strong>{{ displayGrade(scope.row.finalGradeValue) }}</strong>
            <small>{{ scope.row.finalGradeLabel }}</small>
          </span>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无已发布证书" :image-size="72" />
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

function gradeClass(value) {
  const grade = Number(value)
  if (grade >= 9.5) return 'grade-badge--gem'
  if (grade >= 9) return 'grade-badge--mint'
  return 'grade-badge--standard'
}
</script>

<style scoped>
.dashboard-panel {
  min-width: 0;
  border: 1px solid #e2e8e6;
  border-radius: 8px;
  background: #ffffff;
}

.panel-header {
  display: flex;
  min-height: 72px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  border-bottom: 1px solid #edf1f0;
}

.panel-kicker {
  margin: 0 0 4px;
  color: #16766e;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0;
}

.panel-header h2 {
  margin: 0;
  color: #17221f;
  font-size: 17px;
  line-height: 1.2;
}

.recent-table {
  width: 100%;
}

.cert-id {
  color: #2f5f65;
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
  color: #23302d;
  font-size: 13px;
}

.card-brand {
  margin-top: 3px;
  color: #8a9491;
  font-size: 11px;
}

.grade-badge {
  display: inline-flex;
  min-width: 102px;
  align-items: baseline;
  justify-content: flex-end;
  gap: 7px;
  color: #4d5b57;
}

.grade-badge strong {
  font-size: 16px;
  font-variant-numeric: tabular-nums;
}

.grade-badge small {
  max-width: 70px;
  overflow: hidden;
  color: #7a8581;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.grade-badge--gem strong { color: #16766e; }
.grade-badge--mint strong { color: #3d6b8d; }
.grade-badge--standard strong { color: #7a6653; }

:deep(.el-table__inner-wrapper::before) {
  display: none;
}

:deep(.el-table th.el-table__cell) {
  height: 42px;
  background: #f7f9f8;
  color: #697570;
  font-size: 12px;
}

:deep(.el-table td.el-table__cell) {
  height: 54px;
  border-bottom-color: #edf1f0;
}
</style>
