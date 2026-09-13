<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import QRCode from 'qrcode'
import { customerRequest } from '../lib/customer'
import { createCardLabelPrinter, type CardIdentityOrder } from '../../../nxr-frontend-shared/cardLabels'
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
  try{await printer.open(()=>load(true),code=>QRCode.toDataURL(code,{width:240,margin:3,errorCorrectionLevel:'M'}),'en')}
  catch(e){if(current===printGeneration)error.value=String((e as {message?:unknown})?.message||'Unable to prepare labels.')}
  finally{if(current===printGeneration)busy.value=false}
}
watch(()=>props.orderNo,()=>{generation++;printGeneration++;printer.close();data.value=null;error.value='';busy.value=false;const current=generation;void load().catch(e=>{if(current+1===generation)error.value=e instanceof Error?e.message:'Unable to load card labels.'})},{immediate:true})
onBeforeUnmount(()=>{generation++;printGeneration++;printer.close()})
</script>
<template><section class="form-section no-print" data-testid="customer-card-identities"><div class="identity-heading"><div><h2>Individual card labels</h2><p class="muted-copy">Each card has its own intake code. Keep its label with the matching card sleeve before mailing.</p></div><button class="btn-secondary" :disabled="busy" data-testid="customer-print-card-identities" @click="printLabels">{{busy?'Preparing…':'Prepare / print card labels'}}</button></div><p v-if="error" class="form-error" role="alert">{{error}}</p><div v-if="data" class="table-scroll"><table class="portal-table"><thead><tr><th>Card</th><th>Intake code</th><th>Owner</th><th>Submission route</th></tr></thead><tbody><tr v-for="card in data.items" :key="card.orderItemId"><td>{{card.itemNo}} · {{card.cardName}}</td><td><code>{{card.receiptCode||'Assigned when labels are prepared'}}</code></td><td>{{card.ownerDisplayName}}<small v-if="card.clientReference">{{card.clientReference}}</small></td><td>{{card.sourceType==='partner'?'Via '+card.partnerCompanyName:'Direct to NXR'}}</td></tr></tbody></table></div></section></template>
<style scoped>.identity-heading{display:flex;justify-content:space-between;align-items:center;gap:20px}.identity-heading p{margin-top:8px}.identity-heading button{flex-shrink:0}.table-scroll{overflow:auto;margin-top:18px}code{overflow-wrap:anywhere}small{display:block}@media(max-width:760px){.identity-heading{flex-direction:column;align-items:stretch}}</style>
