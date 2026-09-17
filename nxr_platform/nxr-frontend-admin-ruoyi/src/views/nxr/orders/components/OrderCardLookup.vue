<script setup lang="ts">
import { tx } from '@/i18n'
import { ref } from 'vue'
import request from '@/utils/request'
import type { CardIdentity } from '../../../../../../nxr-frontend-shared/cardLabels'
const emit=defineEmits<{openOrder:[id:number]}>()
const code=ref(''),result=ref<CardIdentity|null>(null),busy=ref(false),error=ref('')
async function lookup(){
  if(!code.value.trim()||busy.value)return
  busy.value=true;error.value='';result.value=null
  try{const value=await request({url:'/api/admin/card-identities/lookup',params:{code:code.value.trim()},suppressErrorMessage:true});result.value=value.data??value}
  catch(e:any){error.value=e?.response?.data?.message||e.message||tx('No intake record was found for this card.')}
  finally{busy.value=false}
}
</script>
<template><el-card shadow="never" class="mb12" data-testid="order-card-lookup"><form class="lookup-row" @submit.prevent="lookup"><strong>{{ $tx('Look up card ownership') }}</strong><el-input v-model="code" clearable :placeholder="$tx('Scan an intake QR code or enter an intake code')" aria-:label="$tx('Card intake code')" data-testid="card-lookup-code"/><el-button type="primary" native-type="submit" :loading="busy" data-testid="card-lookup-submit">{{ $tx('Find card') }}</el-button></form><el-alert v-if="error" :title="error" type="error" :closable="false"/><el-descriptions v-if="result" :column="2" border class="lookup-result"><el-descriptions-item :label="$tx('Card')">{{result.cardName}}</el-descriptions-item><el-descriptions-item :label="$tx('Original customer')">{{result.ownerDisplayName}} {{result.clientReference||''}}</el-descriptions-item><el-descriptions-item :label="$tx('Intake code')">{{result.receiptCode}}</el-descriptions-item><el-descriptions-item :label="$tx('Submission source')">{{result.sourceType==='partner'?$tx('Partner: ')+result.partnerCompanyName:$tx('Customer direct to NXR')}}</el-descriptions-item><el-descriptions-item :label="$tx('Return route')">{{result.returnRoute==='via_partner'?$tx('NXR → Partner → Customer'):$tx('NXR → Customer')}}</el-descriptions-item><el-descriptions-item :label="$tx('Order')"><el-button link type="primary" data-testid="card-lookup-open-order" @click="emit('openOrder',result.orderId)">{{result.orderNo}}</el-button></el-descriptions-item></el-descriptions></el-card></template>
<style scoped>.lookup-row{display:flex;gap:12px;align-items:center}.lookup-row strong{white-space:nowrap}.lookup-row .el-input{max-width:400px}.lookup-result,.el-alert{margin-top:14px}@media(max-width:700px){.lookup-row{flex-wrap:wrap}.lookup-row .el-input{max-width:none}}</style>
