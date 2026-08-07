<template>
  <section class="workflow-strip" aria-label="评级处理进度">
    <div class="workflow-strip__heading">
      <span>评级流程</span>
      <strong>{{ formatNumber(pendingWork) }} 项待处理</strong>
    </div>
    <div class="workflow-step">
      <span class="workflow-step__number">01</span>
      <div><strong>资料录入</strong><small>{{ formatNumber(totalSubmissions) }} 条</small></div>
    </div>
    <el-icon class="workflow-arrow"><ArrowRight /></el-icon>
    <div class="workflow-step">
      <span class="workflow-step__number">02</span>
      <div><strong>审批处理</strong><small>{{ formatNumber(pendingReview) }} 条</small></div>
    </div>
    <el-icon class="workflow-arrow"><ArrowRight /></el-icon>
    <div class="workflow-step">
      <span class="workflow-step__number">03</span>
      <div><strong>证书发布</strong><small>{{ formatNumber(approvedReady) }} 条待发布</small></div>
    </div>
  </section>
</template>

<script setup>
import { ArrowRight } from '@element-plus/icons-vue'

defineProps({
  totalSubmissions: { type: Number, default: 0 },
  pendingReview: { type: Number, default: 0 },
  approvedReady: { type: Number, default: 0 },
  pendingWork: { type: Number, default: 0 }
})

function formatNumber(value) {
  return new Intl.NumberFormat('zh-CN').format(value || 0)
}
</script>

<style scoped>
.workflow-strip {
  display: grid;
  min-height: 92px;
  grid-template-columns: minmax(170px, 0.9fr) minmax(150px, 1fr) 20px minmax(150px, 1fr) 20px minmax(170px, 1fr);
  align-items: center;
  gap: 12px;
  margin-top: 16px;
  padding: 15px 20px;
  border: 1px solid var(--nxr-border);
  border-radius: 8px;
  background: var(--nxr-surface);
}

.workflow-strip__heading span,
.workflow-strip__heading strong {
  display: block;
}

.workflow-strip__heading span {
  color: var(--nxr-text-faint);
  font-size: 12px;
}

.workflow-strip__heading strong {
  margin-top: 6px;
  color: var(--nxr-text);
  font-size: 16px;
}

.workflow-step {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 11px;
  padding: 10px 12px;
  border-left: 3px solid var(--nxr-accent);
  background: transparent;
}

.workflow-step__number {
  color: var(--nxr-text-faint);
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
  color: var(--nxr-text);
  font-size: 13px;
}

.workflow-step small {
  margin-top: 4px;
  color: var(--nxr-text-faint);
  font-size: 11px;
}

.workflow-arrow {
  color: var(--nxr-text-placeholder);
}

@media (max-width: 1280px) {
  .workflow-strip {
    grid-template-columns: minmax(150px, 0.8fr) repeat(3, minmax(145px, 1fr));
  }

  .workflow-arrow {
    display: none;
  }
}

@media (max-width: 900px) {
  .workflow-strip {
    grid-template-columns: 1fr;
  }

  .workflow-strip__heading {
    padding-bottom: 10px;
    border-bottom: 1px solid var(--nxr-border-subtle);
  }
}
</style>
