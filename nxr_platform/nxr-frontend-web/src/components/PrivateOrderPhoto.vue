<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { orderPhotoUrl } from '../lib/orderApplication'
const props = defineProps<{ photoId: number | null | undefined; label: string }>()
const url = ref(''), error = ref('')
let generation = 0
watch(() => props.photoId, async id => {
  const current = ++generation
  if (url.value) URL.revokeObjectURL(url.value)
  url.value = ''; error.value = ''
  if (!id) return
  try {
    const next = await orderPhotoUrl(id)
    if (current !== generation) { URL.revokeObjectURL(next); return }
    url.value = next
  } catch (e) { if (current === generation) error.value = e instanceof Error ? e.message : 'Image unavailable' }
}, { immediate: true })
onBeforeUnmount(() => { generation++; if (url.value) URL.revokeObjectURL(url.value) })
</script>
<template><figure v-if="photoId" class="private-photo"><a v-if="url" :href="url" target="_blank" rel="noopener noreferrer"><img :src="url" :alt="label" /></a><span v-else>{{ error || 'Loading image…' }}</span><figcaption>{{ label }}</figcaption></figure></template>
<style scoped>.private-photo{display:inline-flex;flex-direction:column;gap:6px;margin:8px 12px 8px 0;max-width:160px}.private-photo img{max-width:160px;height:120px;object-fit:contain;border-radius:8px}.private-photo figcaption{font-size:12px;color:var(--text-muted,#777)}</style>
