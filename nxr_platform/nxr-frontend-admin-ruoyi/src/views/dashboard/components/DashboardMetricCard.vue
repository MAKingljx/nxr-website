<template>
  <article class="metric-card">
    <div class="metric-card__header">
      <span class="metric-card__icon" aria-hidden="true">
        <el-icon><component :is="icon" /></el-icon>
      </span>
      <span class="metric-card__label">{{ label }}</span>
    </div>
    <strong class="metric-card__value">{{ formattedValue }}</strong>
    <p class="metric-card__detail">{{ detail }}</p>
  </article>
</template>

<script setup>
const props = defineProps({
  label: { type: String, required: true },
  value: { type: Number, default: 0 },
  detail: { type: String, required: true },
  icon: { type: [Object, Function], required: true }
})

const formattedValue = computed(() => new Intl.NumberFormat('zh-CN').format(props.value || 0))
</script>

<style scoped>
.metric-card {
  min-height: 146px;
  padding: 20px;
  border: 1px solid var(--nxr-border);
  border-top: 3px solid var(--nxr-accent);
  border-radius: 8px;
  background: var(--nxr-surface);
}

.metric-card__header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.metric-card__icon {
  display: inline-flex;
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
  flex: 0 0 32px;
  border-radius: 7px;
  background: var(--nxr-accent-soft);
  color: var(--nxr-accent);
  font-size: 17px;
}

.metric-card__label {
  color: var(--nxr-text-muted);
  font-size: 13px;
  font-weight: 600;
}

.metric-card__value {
  display: block;
  margin-top: 14px;
  color: var(--nxr-text-strong);
  font-size: 28px;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.metric-card__detail {
  margin: 10px 0 0;
  color: var(--nxr-text-faint);
  font-size: 12px;
  line-height: 1.45;
}
</style>
