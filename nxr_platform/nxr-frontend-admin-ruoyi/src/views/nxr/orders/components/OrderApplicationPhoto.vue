<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import request from '@/utils/request'
import { tx, localizeBackendMessage } from '@/i18n'
const props = defineProps({ photoId: Number, orderId: Number, label: String })
const url = ref(''), error = ref('')
let generation = 0
watch(() => [props.photoId, props.orderId], async ([id, orderId]) => {
  const current = ++generation
  if (url.value) URL.revokeObjectURL(url.value)
  url.value = ''; error.value = ''
  if (!id) return
  try {
    const blob = await request({ url: `/api/admin/order-photos/${id}`, method: 'get', params: { orderId }, responseType: 'blob' })
    if (current !== generation) return
    if (!blob.type?.startsWith('image/')) throw new Error(tx('Unable to load the image or access is restricted'))
    url.value = URL.createObjectURL(blob)
  } catch (e) { if (current === generation) error.value = localizeBackendMessage(e.message) || tx('Unable to load image') }
}, { immediate: true })
onBeforeUnmount(() => { generation++; if (url.value) URL.revokeObjectURL(url.value) })
</script>
<template><div v-if="photoId" class="application-photo"><el-image v-if="url" :src="url" :preview-src-list="[url]" preview-teleported fit="contain" /><small v-else>{{ error || $tx('Loading image…') }}</small><small>{{ label }}</small></div></template>
<style scoped>.application-photo{display:inline-flex;flex-direction:column;gap:5px;margin:6px 10px 6px 0}.application-photo .el-image{width:84px;height:105px}.application-photo small{font-size:12px}</style>
