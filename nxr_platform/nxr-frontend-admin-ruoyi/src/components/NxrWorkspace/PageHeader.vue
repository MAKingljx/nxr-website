<template>
  <header class="nxr-page-header">
    <div class="nxr-page-header__copy">
      <p v-if="kicker" class="nxr-page-header__kicker">{{ kicker }}</p>
      <h1>{{ title }}</h1>
      <p v-if="summary" class="nxr-page-header__summary">{{ summary }}</p>
    </div>
    <div v-if="$slots.actions" class="nxr-page-header__actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<script setup>
defineProps({
  kicker: { type: String, default: '' },
  title: { type: String, required: true },
  summary: { type: String, default: '' }
})
</script>

<style scoped>
.nxr-page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 22px;
}

.nxr-page-header__copy {
  min-width: 0;
}

.nxr-page-header__kicker {
  margin: 0 0 6px;
  color: var(--nxr-accent);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0;
}

.nxr-page-header h1 {
  margin: 0;
  color: var(--nxr-text-strong);
  font-size: 28px;
  line-height: 1.2;
  letter-spacing: 0;
}

.nxr-page-header__summary {
  margin: 8px 0 0;
  color: var(--nxr-text-muted);
  font-size: 13px;
}

.nxr-page-header__actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 0 0 auto;
}

@media (max-width: 560px) {
  .nxr-page-header {
    align-items: flex-end;
  }

  .nxr-page-header h1 {
    font-size: 23px;
  }
}
</style>
