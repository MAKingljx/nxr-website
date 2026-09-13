<script setup lang="ts">
import { ref } from 'vue'
import request from '@/utils/request'
import type { CardIdentity } from '../../../../../../nxr-frontend-shared/cardLabels'
const emit=defineEmits<{openOrder:[id:number]}>()
const code=ref(''),result=ref<CardIdentity|null>(null),busy=ref(false),error=ref('')
async function lookup(){
  if(!code.value.trim()||busy.value)return
  busy.value=true;error.value='';result.value=null
  try{const value=await request({url:'/api/admin/card-identities/lookup',params:{code:code.value.trim()},suppressErrorMessage:true});result.value=value.data??value}
  catch(e:any){error.value=e?.response?.data?.message||e.message||'未找到这张卡的收件记录。'}
  finally{busy.value=false}
}
</script>
<template><el-card shadow="never" class="mb12" data-testid="order-card-lookup"><form class="lookup-row" @submit.prevent="lookup"><strong>单卡归属查询</strong><el-input v-model="code" clearable placeholder="扫描收卡二维码或输入收卡编号" aria-label="单卡收卡编号" data-testid="card-lookup-code"/><el-button type="primary" native-type="submit" :loading="busy" data-testid="card-lookup-submit">查询卡片</el-button></form><el-alert v-if="error" :title="error" type="error" :closable="false"/><el-descriptions v-if="result" :column="2" border class="lookup-result"><el-descriptions-item label="卡片">{{result.cardName}}</el-descriptions-item><el-descriptions-item label="原始客户">{{result.ownerDisplayName}} {{result.clientReference||''}}</el-descriptions-item><el-descriptions-item label="收卡编号">{{result.receiptCode}}</el-descriptions-item><el-descriptions-item label="送评来源">{{result.sourceType==='partner'?'子代理：'+result.partnerCompanyName:'客户直寄 NXR'}}</el-descriptions-item><el-descriptions-item label="回寄路线">{{result.returnRoute==='via_partner'?'NXR → 子代理 → 客户':'NXR → 客户'}}</el-descriptions-item><el-descriptions-item label="订单"><el-button link type="primary" data-testid="card-lookup-open-order" @click="emit('openOrder',result.orderId)">{{result.orderNo}}</el-button></el-descriptions-item></el-descriptions></el-card></template>
<style scoped>.lookup-row{display:flex;gap:12px;align-items:center}.lookup-row strong{white-space:nowrap}.lookup-row .el-input{max-width:400px}.lookup-result,.el-alert{margin-top:14px}@media(max-width:700px){.lookup-row{flex-wrap:wrap}.lookup-row .el-input{max-width:none}}</style>
