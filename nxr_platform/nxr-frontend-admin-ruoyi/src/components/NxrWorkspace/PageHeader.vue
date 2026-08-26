<template>
  <phoenix-page-header
    class="nxr-page-header"
    :title="title"
    :description="summary"
  >
    <template v-if="kicker" #breadcrumb>
      <span class="nxr-page-header__kicker">{{ kicker }}</span>
    </template>
    <template v-if="$slots.actions" #actions>
      <slot name="actions" />
    </template>
  </phoenix-page-header>
</template>

<script setup>
import PhoenixPageHeader from '@phoenix-gallery/primitives/platform/PhoenixPageHeader.vue'

defineProps({
  kicker: { type: String, default: '' },
  title: { type: String, required: true },
  summary: { type: String, default: '' }
})
</script>

<style scoped>
.nxr-page-header {
  --px-primary: var(--nxr-accent);
  --px-ink: var(--nxr-text-strong);
  --px-muted: var(--nxr-text-muted);
  --px-border: var(--nxr-border);
  --px-surface: var(--nxr-surface);

  grid-template-columns: minmax(0, 1fr) auto;
  margin-bottom: 22px;
}

.nxr-page-header__kicker {
  color: var(--nxr-accent);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0;
}

.nxr-page-header :deep(h1) {
  color: var(--nxr-text-strong);
  letter-spacing: 0;
}

.nxr-page-header :deep(.px-page-header__actions) {
  align-items: center;
}

@media (max-width: 720px) {
  .nxr-page-header {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
