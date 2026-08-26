<template>
  <phoenix-tag
    class="nxr-status-tag"
    :variant="status.tone"
    :size="size"
    :rounded="rounded"
  >
    {{ status.label }}
  </phoenix-tag>
</template>

<script setup>
import { computed } from 'vue'
import PhoenixTag from '@phoenix-gallery/primitives/PhoenixTag.vue'
import { resolveNxrStatus } from '@/utils/nxrStatus'

const props = defineProps({
  code: { type: [String, Number], default: '' },
  label: { type: String, default: '' },
  tone: { type: String, default: '' },
  domain: { type: String, default: 'general' },
  size: {
    type: String,
    default: 'small',
    validator: (value) => ['small', 'medium'].includes(value)
  },
  rounded: { type: Boolean, default: true }
})

const status = computed(() =>
  resolveNxrStatus({
    code: props.code,
    label: props.label,
    tone: props.tone,
    domain: props.domain
  })
)
</script>

<style scoped>
.nxr-status-tag {
  --px-primary: var(--nxr-accent);
  --px-primary-hover: var(--nxr-accent-hover);
  --px-primary-soft: var(--nxr-accent-soft);
  --px-success: var(--nxr-success);
  --px-warning: var(--nxr-warning);
  --px-danger: var(--nxr-danger);
  --px-ink: var(--nxr-text-strong);
  --px-muted: var(--nxr-text-muted);
  --px-border: var(--nxr-border);
  --px-surface: var(--nxr-surface);
  --px-soft: var(--nxr-surface-muted);
}
</style>

