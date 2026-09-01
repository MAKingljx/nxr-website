<template>
  <section class="workflow-strip" :aria-label="$tx('Grading workflow progress')">
    <div class="workflow-strip__heading">
      <span class="workflow-label">{{ $tx('Grading Workflow') }}</span>
      <div class="workflow-metric workflow-metric--primary">
        <strong>{{ formatNumber(pendingWork) }}</strong>
        <small>{{ $tx('pending') }}</small>
      </div>
    </div>
    <div class="workflow-step">
      <span class="workflow-step__number">01</span>
      <div class="workflow-step__content">
        <span class="workflow-label">{{ $tx('Data Entry') }}</span>
        <div class="workflow-metric">
          <strong>{{ formatNumber(totalSubmissions) }}</strong>
          <small>{{ $tx('records') }}</small>
        </div>
      </div>
    </div>
    <el-icon class="workflow-arrow"><ArrowRight /></el-icon>
    <div class="workflow-step">
      <span class="workflow-step__number">02</span>
      <div class="workflow-step__content">
        <span class="workflow-label">{{ $tx('Review') }}</span>
        <div class="workflow-metric">
          <strong>{{ formatNumber(pendingReview) }}</strong>
          <small>{{ $tx('records') }}</small>
        </div>
      </div>
    </div>
    <el-icon class="workflow-arrow"><ArrowRight /></el-icon>
    <div class="workflow-step">
      <span class="workflow-step__number">03</span>
      <div class="workflow-step__content">
        <span class="workflow-label">{{ $tx('Publication') }}</span>
        <div class="workflow-metric">
          <strong>{{ formatNumber(approvedReady) }}</strong>
          <small>{{ $tx('ready') }}</small>
        </div>
      </div>
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
  return new Intl.NumberFormat('en-US').format(value || 0)
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

.workflow-strip__heading,
.workflow-step__content {
  min-width: 0;
}

.workflow-label {
  display: block;
  overflow: hidden;
  color: var(--nxr-text-faint);
  font-size: 11px;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-metric {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 5px;
  margin-top: 5px;
  white-space: nowrap;
}

.workflow-metric strong {
  color: var(--nxr-text);
  font-size: 22px;
  font-variant-numeric: tabular-nums;
  font-weight: 750;
  letter-spacing: -0.025em;
  line-height: 1;
}

.workflow-metric--primary strong {
  font-size: 24px;
}

.workflow-metric small {
  overflow: hidden;
  color: var(--nxr-text-faint);
  font-size: 10px;
  font-weight: 500;
  line-height: 1.2;
  text-overflow: ellipsis;
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
