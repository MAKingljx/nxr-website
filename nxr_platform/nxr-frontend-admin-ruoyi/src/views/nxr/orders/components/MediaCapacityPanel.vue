<script setup>
import { onMounted, ref } from 'vue'
import request from '@/utils/request'
const status = ref(null), loading = ref(false), error = ref('')
const size = value => `${(Number(value || 0) / 1024 ** 3).toFixed(2)} GB`
async function refresh() {
  loading.value = true; error.value = ''
  try { status.value = (await request({ url: '/api/admin/order-photos/capacity', method: 'get' })).data }
  catch (e) { error.value = e.message || '容量读取失败' }
  finally { loading.value = false }
}
onMounted(refresh)
</script>
<template><section class="media-capacity"><div class="capacity-heading"><h3>图片存储容量</h3><el-button :loading="loading" @click="refresh">刷新容量</el-button></div><el-alert v-if="error" :title="error" type="error" :closable="false" /><template v-if="status"><el-alert :type="status.uploadsAllowed ? 'success' : 'error'" :title="status.uploadsAllowed ? '图片上传空间充足' : '剩余空间低于保留值，已暂停新图片上传'" :closable="false" /><el-descriptions :column="2" border><el-descriptions-item label="磁盘可用">{{ size(status.usableBytes) }}</el-descriptions-item><el-descriptions-item label="磁盘总量">{{ size(status.totalBytes) }}</el-descriptions-item><el-descriptions-item label="保留空间">{{ size(status.minimumFreeBytes) }}</el-descriptions-item><el-descriptions-item label="检查时间">{{ new Date(status.checkedAt).toLocaleString() }}</el-descriptions-item></el-descriptions><p>客户申请图片保留原图，预览使用压缩缩略图。已绑定订单的图片不自动删除。此处显示媒体所在磁盘的容量。</p></template></section></template>
<style scoped>.media-capacity{display:grid;gap:16px;padding:16px 0}.capacity-heading{display:flex;align-items:center;justify-content:space-between}.capacity-heading h3{margin:0}.media-capacity p{margin:0;color:var(--el-text-color-secondary);font-size:13px;line-height:1.7}</style>
