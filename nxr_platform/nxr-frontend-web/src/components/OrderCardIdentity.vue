<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import QRCode from 'qrcode'
import { customerRequest } from '../lib/customer'
import { createCardLabelPrinter, type CardIdentityOrder } from '../../../nxr-frontend-shared/cardLabels'
const { locale } = useI18n()
const text = (en: string, zh: string) => locale.value === 'zh-CN' ? zh : en
const props=defineProps<{orderNo:string}>()
const data=ref<CardIdentityOrder|null>(null),error=ref(''),busy=ref(false)
const printer=createCardLabelPrinter()
let generation=0,printGeneration=0
async function load(allocate=false){
  const current=++generation,no=props.orderNo
  const value=await customerRequest<CardIdentityOrder>('/api/customer/orders/'+encodeURIComponent(no)+'/card-identities',{method:allocate?'POST':'GET'})
  if(current===generation)data.value=value
  return value
}
async function printLabels(){
  if(busy.value)return
  const current=++printGeneration;busy.value=true;error.value=''
  try{await printer.open(()=>load(true),code=>QRCode.toDataURL(code,{width:240,margin:3,errorCorrectionLevel:'M'}),locale.value === 'zh-CN' ? 'zh-CN' : 'en')}
  catch(e){if(current===printGeneration)error.value=String((e as {message?:unknown})?.message||text('Unable to prepare labels.', '标签生成失败。'))}
  finally{if(current===printGeneration)busy.value=false}
}
watch(()=>props.orderNo,()=>{generation++;printGeneration++;printer.close();data.value=null;error.value='';busy.value=false;const current=generation;void load().catch(e=>{if(current+1===generation)error.value=e instanceof Error?e.message:text('Unable to load card labels.', '收卡标签加载失败。')})},{immediate:true})
onBeforeUnmount(()=>{generation++;printGeneration++;printer.close()})
</script>
<template><section class="form-section no-print" data-testid="customer-card-identities"><div class="identity-heading"><div><h2>{{ text('Individual card labels', '逐卡收卡标签') }}</h2><p class="muted-copy">{{ text('Each card has its own intake code. Keep its label with the matching card sleeve before mailing.', '每张卡有独立的收卡编号。寄出前请将标签与对应的卡套放在一起。') }}</p></div><button class="btn-secondary" :disabled="busy" data-testid="customer-print-card-identities" @click="printLabels">{{busy ? text('Preparing…', '准备中…') : text('Prepare / print card labels', '生成 / 打印收卡标签')}}</button></div><p v-if="error" class="form-error" role="alert">{{error}}</p><div v-if="data" class="table-scroll"><table class="portal-table"><thead><tr><th>{{ text('Card', '卡片') }}</th><th>{{ text('Intake code', '收卡编号') }}</th><th>{{ text('Owner', '原始客户') }}</th><th>{{ text('Submission route', '送评路线') }}</th></tr></thead><tbody><tr v-for="card in data.items" :key="card.orderItemId"><td>{{card.itemNo}} · {{card.cardName}}</td><td><code>{{card.receiptCode||text('Assigned when labels are prepared', '生成标签时分配')}}</code></td><td>{{card.ownerDisplayName}}<small v-if="card.clientReference">{{card.clientReference}}</small></td><td>{{card.sourceType==='partner'?text('Via ', '经由子代理：')+card.partnerCompanyName:text('Direct to NXR', '直寄 NXR')}}</td></tr></tbody></table></div></section></template>
<style scoped>.identity-heading{display:flex;justify-content:space-between;align-items:center;gap:20px}.identity-heading p{margin-top:8px}.identity-heading button{flex-shrink:0}.table-scroll{overflow:auto;margin-top:18px}code{overflow-wrap:anywhere}small{display:block}@media(max-width:760px){.identity-heading{flex-direction:column;align-items:stretch}}</style>
