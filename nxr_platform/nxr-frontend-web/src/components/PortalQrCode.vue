<script setup lang="ts">
import { ref, watch } from 'vue'
import QRCode from 'qrcode'
const props = defineProps<{ value: string; label: string }>()
const src = ref('')
watch(() => props.value, async value => {
  src.value = ''
  if (!value) return
  try {
    const result = await QRCode.toDataURL(value, { width: 240, margin: 4, errorCorrectionLevel: 'M' })
    if (props.value === value) src.value = result
  } catch { /* The visible reference remains available if rendering fails. */ }
}, { immediate: true })
</script>
<template><img v-if="src" :src="src" :alt="label" width="240" height="240" class="portal-qr" /><p v-else class="muted-copy">QR code unavailable. Use the displayed reference.</p></template>
<style scoped>.portal-qr{display:block;max-width:100%;height:auto;background:white;border-radius:8px}</style>
