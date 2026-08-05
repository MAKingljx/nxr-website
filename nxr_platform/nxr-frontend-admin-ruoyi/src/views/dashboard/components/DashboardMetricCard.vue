<template>
  <article class="metric-card" :class="`metric-card--${tone}`">
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
  tone: { type: String, default: 'blue' },
  icon: { type: [Object, Function], required: true }
})

const formattedValue = computed(() => new Intl.NumberFormat('zh-CN').format(props.value || 0))
</script>

<style scoped>
.metric-card {
  min-height: 146px;
  padding: 20px;
  border: 1px solid #e2e8e6;
  border-top: 3px solid var(--metric-accent);
  border-radius: 8px;
  background: #ffffff;
}

.metric-card--blue { --metric-accent: #3d6b8d; --metric-soft: #edf4f8; }
.metric-card--amber { --metric-accent: #b66a28; --metric-soft: #fff5e9; }
.metric-card--teal { --metric-accent: #16766e; --metric-soft: #eaf6f3; }
.metric-card--green { --metric-accent: #3b7652; --metric-soft: #edf6ef; }

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
  background: var(--metric-soft);
  color: var(--metric-accent);
  font-size: 17px;
}

.metric-card__label {
  color: #65716e;
  font-size: 13px;
  font-weight: 600;
}

.metric-card__value {
  display: block;
  margin-top: 14px;
  color: #17221f;
  font-size: 28px;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.metric-card__detail {
  margin: 10px 0 0;
  color: #8a9491;
  font-size: 12px;
  line-height: 1.45;
}
</style>
