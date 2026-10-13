<template>
  <phoenix-metric-card
    class="metric-card"
    :class="{ 'metric-card--interactive': interactive }"
    :label="label"
    :value="value"
    :description="detail"
    appearance="modern"
    :role="interactive ? 'link' : undefined"
    :tabindex="interactive ? 0 : undefined"
    @click="activate"
    @keydown.enter.prevent="activate"
    @keydown.space.prevent="activate"
  >
    <template #icon>
      <el-icon><component :is="icon" /></el-icon>
    </template>
  </phoenix-metric-card>
</template>

<script setup>
import PhoenixMetricCard from '@phoenix-gallery/primitives/analytics/PhoenixMetricCard.vue'

const props = defineProps({
  label: { type: String, required: true },
  value: { type: Number, default: 0 },
  detail: { type: String, required: true },
  icon: { type: [Object, Function], required: true },
  interactive: { type: Boolean, default: false }
})

const emit = defineEmits(['activate'])

function activate() {
  if (props.interactive) emit('activate')
}
</script>

<style scoped>
.metric-card {
  --px-primary: var(--nxr-accent);
  --px-border: var(--nxr-border);
  --px-surface: var(--nxr-surface);
  --px-soft: var(--nxr-surface-subtle);
  --px-ink: var(--nxr-text-strong);
  --px-muted: var(--nxr-text-muted);
  --px-success: var(--nxr-success);
  --px-danger: var(--nxr-danger);
  --px-analytics-primary: var(--nxr-accent);
  --px-analytics-primary-soft: var(--nxr-accent-soft);
  --px-analytics-border: var(--nxr-border);
  --px-analytics-surface: var(--nxr-surface);
  --px-analytics-soft: var(--nxr-surface-subtle);
  --px-analytics-ink: var(--nxr-text-strong);
  --px-analytics-muted: var(--nxr-text-muted);
  --px-analytics-success: var(--nxr-success);
  --px-analytics-danger: var(--nxr-danger);

  border-top: 3px solid var(--nxr-accent);
  border-radius: 10px;
  background: var(--nxr-surface);
  box-shadow: none;
}

.metric-card :deep(.px-metric-card__body) {
  min-height: 143px;
}

.metric-card--interactive {
  cursor: pointer;
}

.metric-card--interactive:hover,
.metric-card--interactive:focus-visible {
  border-color: var(--nxr-accent);
  outline: none;
  box-shadow: 0 8px 22px rgb(48 84 111 / 12%);
  transform: translateY(-1px);
}
</style>
