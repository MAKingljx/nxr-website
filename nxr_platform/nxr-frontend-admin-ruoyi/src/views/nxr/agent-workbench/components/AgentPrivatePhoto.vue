<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useAgentApi } from '../lib/agentWorkbench'
const props = defineProps<{ photoId?: number | null; label: string }>()
const api = useAgentApi(), url = ref(''), error = ref('')
let generation = 0
watch(() => props.photoId, async id => {
  const current = ++generation
  if (url.value) URL.revokeObjectURL(url.value)
  url.value = ''; error.value = ''
  if (!id) return
  try {
    const blob = await api.fetchPhoto(id)
    if (current !== generation || !api.isActive()) return
    if (!blob.type?.startsWith('image/')) throw new Error('图片读取失败或无权限。')
    url.value = URL.createObjectURL(blob)
  } catch (e) { if (current === generation) error.value = e instanceof Error ? e.message : '图片读取失败。' }
}, { immediate: true })
onBeforeUnmount(() => { generation++; if (url.value) URL.revokeObjectURL(url.value) })
</script>
<template><figure v-if="photoId" class="agent-private-photo"><a v-if="url" :href="url" target="_blank" rel="noopener noreferrer"><img :src="url" :alt="label" /></a><span v-else>{{ error || '读取图片…' }}</span><figcaption>{{ label }}</figcaption></figure></template>
<style scoped>.agent-private-photo{display:inline-flex;flex-direction:column;gap:5px;margin:8px 12px 8px 0;max-width:150px}.agent-private-photo img{width:130px;height:120px;object-fit:contain;border:1px solid var(--el-border-color);border-radius:5px}.agent-private-photo figcaption,.agent-private-photo span{font-size:12px;color:var(--el-text-color-secondary)}</style>
