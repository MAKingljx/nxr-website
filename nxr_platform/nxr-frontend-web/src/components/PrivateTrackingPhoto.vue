<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { apiBaseUrl } from '../lib/api'
const props = defineProps<{ token: string; accessToken: string; cardId: number; side: 'front' | 'back'; available: boolean }>()
const { t } = useI18n()
const url = ref(''), failed = ref(false), enlarged = ref(false)
let generation = 0, controller: AbortController | undefined
function clear() { controller?.abort(); controller = undefined; if (url.value) URL.revokeObjectURL(url.value); url.value = ''; enlarged.value = false }
async function load() {
  const current = ++generation; clear(); failed.value = false
  if (!props.available || !props.accessToken) return
  const request = new AbortController(); controller = request
  try {
    const response = await fetch(`${apiBaseUrl}/api/public/merchant-order-tracking/photos/${encodeURIComponent(props.cardId)}/${props.side}`, {
      headers: { 'X-NXR-Tracking-Token': props.token, 'X-NXR-Tracking-Access': props.accessToken }, signal: request.signal, cache: 'no-store', credentials: 'omit', referrerPolicy: 'no-referrer'
    })
    if (!response.ok) throw new Error('Image unavailable')
    const blob = await response.blob()
    if (current === generation && !request.signal.aborted) url.value = URL.createObjectURL(blob)
  } catch { if (current === generation && !request.signal.aborted) failed.value = true }
}
watch(() => [props.token, props.accessToken, props.cardId, props.side, props.available], load, { immediate: true })
onBeforeUnmount(() => { generation++; clear() })
</script>
<template>
  <figure class="tracking-photo">
    <button v-if="url" type="button" class="photo-button" :aria-label="t('tracking.enlargePhoto', { side: t(`tracking.${side}`) })" @click="enlarged = true"><img :src="url" :alt="t(`tracking.${side}`)" /></button>
    <span v-else>{{ t(!available ? 'tracking.noPhoto' : failed ? 'tracking.photoUnavailable' : 'tracking.photoLoading') }}</span>
    <figcaption>{{ t(`tracking.${side}`) }}</figcaption>
    <div v-if="enlarged && url" class="photo-overlay" role="dialog" aria-modal="true" :aria-label="t(`tracking.${side}`)" @click.self="enlarged = false" @keydown.esc="enlarged = false"><button type="button" class="photo-close" @click="enlarged = false">{{ t('tracking.closePhoto') }}</button><img :src="url" :alt="t(`tracking.${side}`)" /></div>
  </figure>
</template>
<style scoped>
.tracking-photo{display:flex;flex-direction:column;gap:5px;margin:0;min-width:0}.tracking-photo figcaption,.tracking-photo span{font-size:13px;color:var(--text-muted,#777)}.photo-button{padding:0;border:0;background:none;cursor:pointer;text-align:left}.photo-button img{width:140px;height:120px;max-width:100%;object-fit:contain;border:1px solid var(--border,#ddd);border-radius:6px}.photo-overlay{position:fixed;inset:0;z-index:2000;background:rgba(0,0,0,.85);display:flex;align-items:center;justify-content:center;padding:50px 16px 16px}.photo-overlay img{max-width:100%;max-height:100%;object-fit:contain}.photo-close{position:absolute;right:20px;top:12px;z-index:1;padding:8px 12px}
</style>
