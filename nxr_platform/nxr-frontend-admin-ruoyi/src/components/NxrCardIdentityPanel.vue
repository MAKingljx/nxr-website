<script setup lang="ts">
import { activeLocale, tx } from '@/i18n'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import QRCode from 'qrcode'
import request from '@/utils/request'
import { createCardLabelPrinter, type CardIdentityOrder } from '../../../nxr-frontend-shared/cardLabels'
const props = defineProps<{ orderId?: number; orderNo?: string; companyId?: number; compact?: boolean }>()
const emit = defineEmits<{ loaded: [value: CardIdentityOrder] }>()
const value = ref<CardIdentityOrder | null>(null), loading = ref(false), printing = ref(false), error = ref('')
const printer = createCardLabelPrinter()
const endpoint = computed(() => props.orderId ? '/api/admin/orders/' + props.orderId + '/card-identities' : props.orderNo && props.companyId ? '/api/admin/agent/orders/' + encodeURIComponent(props.orderNo) + '/card-identities' : '')
let generation = 0, printGeneration = 0
async function load(allocate = false) {
  const current = ++generation, url = endpoint.value
  if (!url) throw new Error(tx('Select a submission order first.'))
  loading.value = true; error.value = ''
  try {
    const result = await request({ url, method: allocate ? 'post' : 'get', headers: { repeatSubmit: false, ...(props.companyId ? {'X-NXR-Agent-Id':String(props.companyId)} : {}) }, suppressErrorMessage: true })
    const record = (result.data ?? result) as CardIdentityOrder
    if (current === generation) { value.value = record; emit('loaded', record) }
    return record
  } catch (e: any) { if (current === generation) error.value = e?.response?.data?.message || e.message || tx('Unable to load card identities.'); throw e }
  finally { if (current === generation) loading.value = false }
}
async function printLabels() {
  if (printing.value) return
  const current = ++printGeneration; printing.value = true
  try { await printer.open(() => load(true), code => QRCode.toDataURL(code, { width:240,margin:3,errorCorrectionLevel:'M' }), activeLocale() === 'zh-CN' ? 'zh-CN' : 'en') }
  catch(e) { if(current === printGeneration) error.value = String((e as {message?:unknown})?.message || tx('Unable to prepare labels.')) }
  finally { if(current === printGeneration) printing.value = false }
}
watch(() => [endpoint.value, props.companyId], () => {
  generation++;printGeneration++;printer.close();value.value=null;printing.value=false
  if(endpoint.value)void load().catch(()=>{})
}, { immediate:true })
onBeforeUnmount(() => {generation++;printGeneration++;printer.close()})
</script>
<template><section class="card-identity-panel" data-testid="card-identity-panel"><div class="identity-heading"><div><strong>{{ $tx('Card ownership and intake labels') }}</strong><p>{{ $tx('Each card has its own intake code, linked to its certificate after grading.') }}</p></div><el-button type="primary" plain :loading="printing" :disabled="loading||!endpoint" data-testid="print-card-identities" @click="printLabels">{{ $tx('Prepare / print intake labels') }}</el-button></div><el-alert v-if="error" :title="error" type="error" :closable="false"/><template v-if="value&&!compact"><el-table :data="value.items" border size="small"><el-table-column prop="itemNo" :label="$tx('No.')" width="65"/><el-table-column prop="cardName" :label="$tx('Card')" min-width="150"/><el-table-column :label="$tx('Intake code')" min-width="220"><template #default="{row}"><code>{{row.receiptCode||$tx('Not yet assigned')}}</code></template></el-table-column><el-table-column :label="$tx('Original customer')" min-width="150"><template #default="{row}">{{row.ownerDisplayName}}<small v-if="row.clientReference">{{row.clientReference}}</small></template></el-table-column><el-table-column :label="$tx('Submission source')" min-width="160"><template #default="{row}">{{row.sourceType==='partner'?$tx('Partner: ')+row.partnerCompanyName:$tx('Customer direct to NXR')}}</template></el-table-column><el-table-column :label="$tx('Return route')" min-width="160"><template #default="{row}">{{row.returnRoute==='via_partner'?$tx('NXR → Partner → Customer'):$tx('NXR → Customer')}}</template></el-table-column><el-table-column :label="$tx('Certificate ID')" min-width="130"><template #default="{row}">{{row.certId||$tx('Linked after grading')}}</template></el-table-column></el-table></template></section></template>
<style scoped>.card-identity-panel{display:grid;gap:12px;margin:18px 0}.identity-heading{display:flex;justify-content:space-between;align-items:center;gap:16px}.identity-heading p{margin:6px 0 0;color:var(--el-text-color-secondary);font-size:13px}code{overflow-wrap:anywhere}small{display:block;color:var(--el-text-color-secondary)}@media(max-width:700px){.identity-heading{align-items:stretch;flex-direction:column}}</style>
