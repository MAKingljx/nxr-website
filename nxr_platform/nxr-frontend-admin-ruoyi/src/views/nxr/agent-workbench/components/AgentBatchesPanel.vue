<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { agentDateLabel, agentStatusLabel, emptyAgentPage, formatMoney, privateTrackingUrl, useAgentApi, useAgentActions, type AgentBatch, type AgentOrder, type AgentAdmission, type AgentWallet } from '../lib/agentWorkbench'
import AgentPagination from './AgentPagination.vue'
import AgentPrivatePhoto from './AgentPrivatePhoto.vue'
import NxrCardIdentityPanel from '@/components/NxrCardIdentityPanel.vue'
const api = useAgentApi(), route = useRoute(), rows = ref(emptyAgentPage<AgentBatch>()), batch = ref<AgentBatch | null>(null)
const order = ref<AgentOrder | null>(null), admission = ref<AgentAdmission | null>(null), wallets = ref<AgentWallet[]>([]), accepted = ref(false), loading = ref(false), orderLoading = ref(false)
const packingSlip = ref<Awaited<ReturnType<typeof api.fetchPackingSlip>> | null>(null)
const supplementNote = ref(''), supplementPhotos = ref<number[]>([])
const trackingLinks = ref<Record<string,string>>({}), revokedLinks = ref<Record<string,boolean>>({})
const shipment = reactive({ carrierName:'',trackingNumber:'',note:'' }), { busy,error,success,run } = useAgentActions()
const canInbound = computed(() => Boolean(batch.value?.orders?.length && batch.value.orders.every(item => ['awaiting_inbound','inbound_shipped'].includes(item.statusCode))))
const available = computed(() => wallets.value.find(wallet => wallet.currencyCode === order.value?.currencyCode)?.balance || 0)
let generation = 0, orderGeneration = 0
async function load(page = 1) { loading.value=true; try { rows.value = await api.fetchBatches(page) } catch(e) { error.value=e instanceof Error?e.message:'批次加载失败。' } finally {loading.value=false} }
async function openBatch(batchNo: string) {
  if(busy.value)return
  trackingLinks.value={};revokedLinks.value={}
  const current=++generation; orderGeneration++; batch.value=null; order.value=null; admission.value=null; packingSlip.value=null; orderLoading.value=false; loading.value=true; error.value=''
  try { const value=await api.fetchBatch(batchNo);if(current===generation)batch.value=value }
  catch(e){if(current===generation)error.value=e instanceof Error?e.message:'批次详情加载失败。'}
  finally{if(current===generation)loading.value=false}
}
async function openOrder(orderNo: string) {
  if(busy.value)return
  const current=++orderGeneration;orderLoading.value=true;order.value=null;admission.value=null;packingSlip.value=null;accepted.value=false;supplementNote.value='';supplementPhotos.value=[];error.value=''
  try { const [record,review,balances]=await Promise.all([api.fetchOrder(orderNo),api.fetchAdmission(orderNo),api.fetchWallets()]);if(current===orderGeneration){order.value=record;admission.value=review;wallets.value=balances} }
  catch(e){if(current===orderGeneration)error.value=e instanceof Error?e.message:'订单详情加载失败。'}
  finally{if(current===orderGeneration)orderLoading.value=false}
}
async function refreshCurrent() { try{if(order.value)await openOrder(order.value.orderNo);if(batch.value)batch.value=await api.fetchBatch(batch.value.batchNo);await load(rows.value.page)}catch(e){if(api.isActive())error.value=e instanceof Error?e.message:'操作已保存，请刷新查看最新状态。'} }
async function acceptTerms() {
  if(!order.value||!admission.value?.canAcceptTerms||!accepted.value)return
  const no=order.value.orderNo,payload={termsVersion:admission.value.termsVersion,acceptedQuotedAmount:Number(admission.value.quoteAmount),acceptedCurrency:admission.value.quoteCurrency}
  if(await run(`order:${no}:terms`,payload,key=>api.acceptOrderTerms(no,payload,key),value=>{admission.value=value},'已确认订单与服务条款。'))await refreshCurrent()
}
async function pay() {
  if(!order.value||!admission.value?.canPay)return
  const no=order.value.orderNo,payload={orderNo:no,amount:order.value.totalAmount,currency:order.value.currencyCode}
  if(await run(`order:${no}:pay`,payload,key=>api.payFromWallet(no,key),value=>{order.value=value},'订单已使用企业余额付款。'))await refreshCurrent()
}
async function inbound() {
  if(!batch.value||!canInbound.value)return
  const no=batch.value.batchNo,payload={...shipment,direction:'inbound',directionCode:'inbound'}
  if(await run(`batch:${no}:inbound`,payload,key=>api.addBatchInbound(no,payload,key),()=>{shipment.carrierName='';shipment.trackingNumber='';shipment.note=''},'已登记寄往 NXR 的主包裹。'))await refreshCurrent()
}
async function uploadSupplement(event:Event){
  const input=event.target as HTMLInputElement,file=input.files?.[0]
  if(!file||!order.value)return
  if(!['image/jpeg','image/png'].includes(file.type)||file.size>15*1024*1024){error.value='请选择 15 MB 以内的 JPG 或 PNG。';input.value='';return}
  await run(`order:${order.value.orderNo}:photo`,{name:file.name,size:file.size,lastModified:file.lastModified},()=>api.uploadSupplementalPhoto(file),value=>{supplementPhotos.value.push(value.id)},'补充照片已上传。')
  input.value=''
}
async function resubmit(){if(!order.value||!admission.value?.canResubmit)return;const no=order.value.orderNo,payload={note:supplementNote.value.trim(),supplementalPhotoIds:[...supplementPhotos.value]};if(await run(`order:${no}:resubmit`,payload,key=>api.resubmitOrder(no,payload,key),value=>{admission.value=value},'补充资料已提交审核。'))await refreshCurrent()}
async function rotateLink(orderNo:string){
  if(!batch.value)return
  const batchNo=batch.value.batchNo
  await run(`tracking:${batchNo}:${orderNo}:rotate`,{batchNo,orderNo},()=>api.rotateTrackingLink(batchNo,orderNo),value=>{trackingLinks.value[orderNo]=privateTrackingUrl(value.trackingUrl);revokedLinks.value[orderNo]=false},'客户查询链接已更新，旧链接已失效。')
}
async function revokeLink(orderNo:string){
  if(!batch.value)return
  const batchNo=batch.value.batchNo
  await run(`tracking:${batchNo}:${orderNo}:revoke`,{batchNo,orderNo},()=>api.revokeTrackingLink(batchNo,orderNo),()=>{delete trackingLinks.value[orderNo];revokedLinks.value[orderNo]=true},'客户查询链接已撤销。')
}
async function copyLink(orderNo:string){const link=trackingLinks.value[orderNo];if(!link)return;try{await navigator.clipboard.writeText(link);success.value='客户查询链接已复制。'}catch{error.value='浏览器未允许复制，请选中链接后手动复制。'}}
async function loadPackingSlip(){if(!order.value)return;try{packingSlip.value=await api.fetchPackingSlip(order.value.orderNo)}catch(e){error.value=e instanceof Error?e.message:'收卡信息加载失败。'}}
async function followRoute(){if(typeof route.query.batch==='string')await openBatch(route.query.batch);if(typeof route.query.order==='string')await openOrder(route.query.order)}
watch(()=>[route.query.batch,route.query.order],followRoute)
onMounted(async()=>{await load();await followRoute()})
</script>
<template><section data-testid="agent-batches-panel"><div class="agent-toolbar"><h2>送评批次</h2><el-button :loading="loading" :disabled="busy" @click="load()">刷新批次</el-button></div><el-alert v-if="error" :title="error" type="error" :closable="false" /><el-alert v-if="success" :title="success" type="success" :closable="false" /><div class="agent-batch-grid"><button v-for="item in rows.items" :key="item.batchNo" type="button" class="agent-record" :class="{selected:batch?.batchNo===item.batchNo}" :disabled="busy" @click="openBatch(item.batchNo)"><strong>{{ item.batchName || item.batchNo }}</strong><span>{{ item.batchNo }}</span><span>{{ agentStatusLabel(item.statusCode) }}</span></button></div><el-empty v-if="!rows.items.length&&!loading" description="暂无送评批次，请先将客户来件清点入库。" /><AgentPagination v-bind="rows" :disabled="loading||busy" @change="load" />
  <template v-if="batch"><div class="agent-toolbar"><h3>{{ batch.batchNo }}</h3><span>{{ agentStatusLabel(batch.statusCode) }}</span></div><el-table :data="batch.orders||[]" border><el-table-column prop="clientReference" label="客户编号" min-width="150" /><el-table-column prop="clientDisplayName" label="客户" min-width="130" /><el-table-column label="订单" min-width="190"><template #default="{row}"><el-button link type="primary" :disabled="busy" :data-testid="`agent-open-order-${row.orderNo}`" @click="openOrder(row.orderNo)">{{row.orderNo}}</el-button></template></el-table-column><el-table-column label="卡片" width="80"><template #default="{row}">{{row.totalCardCount??row.cardCount}}</template></el-table-column><el-table-column label="状态" min-width="140"><template #default="{row}">{{agentStatusLabel(row.statusCode==='admission_review'&&row.admissionStatus?row.admissionStatus:row.statusCode)}}</template></el-table-column><el-table-column label="客户私密查询" min-width="260"><template #default="{row}"><div class="tracking-actions"><el-button link type="primary" :disabled="busy" :data-testid="`submission-tracking-create-${row.orderNo}`" @click="rotateLink(row.orderNo)">{{trackingLinks[row.orderNo]?'更新链接':'生成链接'}}</el-button><el-button v-if="trackingLinks[row.orderNo]" link :disabled="busy" :data-testid="`submission-tracking-copy-${row.orderNo}`" @click="copyLink(row.orderNo)">复制</el-button><el-button v-if="(trackingLinks[row.orderNo]||row.trackingTokenHint)&&!revokedLinks[row.orderNo]" link type="danger" :disabled="busy" :data-testid="`submission-tracking-revoke-${row.orderNo}`" @click="revokeLink(row.orderNo)">撤销</el-button><span v-if="revokedLinks[row.orderNo]">已撤销</span></div><el-input v-if="trackingLinks[row.orderNo]" :model-value="trackingLinks[row.orderNo]" readonly aria-label="客户私密查询链接" @focus="($event.target as HTMLInputElement).select()" /></template></el-table-column></el-table><p class="muted-copy">查询链接只显示该客户的订单进度；更新链接会使旧链接失效，请仅交给对应客户。</p><h3>主包裹物流</h3><p v-if="!batch.shipments?.length" class="muted-copy">暂未登记物流。</p><el-table v-else :data="batch.shipments" border><el-table-column label="方向" width="130"><template #default="{row}">{{row.directionCode==='inbound'?'寄往 NXR':'NXR 寄回子代理'}}</template></el-table-column><el-table-column prop="carrierName" label="快递公司" /><el-table-column prop="trackingNumber" label="运单号" min-width="180" /><el-table-column label="状态"><template #default="{row}">{{agentStatusLabel(row.statusCode)}}</template></el-table-column></el-table><form v-if="canInbound" class="portal-form agent-operation" @submit.prevent="inbound"><h3>登记寄往 NXR 的主包裹</h3><div class="form-grid"><label>快递公司<input v-model="shipment.carrierName" required maxlength="128" :disabled="busy" /></label><label>运单号<input v-model="shipment.trackingNumber" required maxlength="128" :disabled="busy" /></label></div><button class="btn-primary" :disabled="busy" data-testid="agent-save-batch-inbound">保存主包裹物流</button></form></template>
  <p v-if="orderLoading" role="status">加载订单…</p><section v-if="order&&admission" class="agent-order-detail" data-testid="agent-order-detail"><div class="agent-toolbar"><h3>订单 {{order.orderNo}}</h3><el-button :disabled="busy" @click="openOrder(order.orderNo)">刷新订单</el-button></div><div class="agent-quote"><strong>{{formatMoney(order.totalAmount,order.currencyCode)}} · {{order.totalCardCount}} 张卡片</strong><span>{{agentStatusLabel(admission.admissionStatus||order.statusCode)}}</span><span v-if="admission.paymentDueAtIso">付款截止：{{agentDateLabel(admission.paymentDueAtIso)}}</span><span v-if="admission.decisionNote">审核意见：{{admission.decisionNote}}</span></div><form v-if="admission.canResubmit" class="portal-form agent-operation" @submit.prevent="resubmit"><h3>补充受理资料</h3><label>补充说明<textarea v-model="supplementNote" required maxlength="2000" :disabled="busy" /></label><label>补充照片（选填）<input type="file" accept="image/jpeg,image/png" :disabled="busy" @change="uploadSupplement" /></label><div><AgentPrivatePhoto v-for="id in supplementPhotos" :key="id" :photo-id="id" label="补充照片" /></div><button class="btn-primary" :disabled="busy" data-testid="agent-resubmit-order">提交补充资料</button></form><form v-if="admission.canAcceptTerms" class="portal-form agent-operation" @submit.prevent="acceptTerms"><h3>确认报价与服务条款</h3><p class="agent-terms">{{admission.termsText}}</p><label class="agent-checkbox"><input v-model="accepted" type="checkbox" required :disabled="busy" data-testid="agent-accept-terms-checkbox" />我已阅读并接受当前报价与服务条款</label><button class="btn-primary" :disabled="busy||!accepted" data-testid="agent-accept-order-terms">确认条款</button></form><div v-if="admission.canPay" class="agent-operation"><p>可用 {{order.currencyCode}} 余额：{{formatMoney(available,order.currencyCode)}}</p><el-button type="primary" :disabled="busy||available<Number(order.totalAmount)" data-testid="agent-pay-order-wallet" @click="pay">使用企业余额支付 {{formatMoney(order.totalAmount,order.currencyCode)}}</el-button><router-link :to="{path:'/nxr/submission-workbench',query:{tab:'wallet',company:api.companyId}}">查看余额 / 充值</router-link></div><NxrCardIdentityPanel :key="order.orderNo" :order-no="order.orderNo" :company-id="api.companyId" /><h3>订单卡片</h3><div class="agent-inventory-cards"><article v-for="card in order.items" :key="card.id" class="agent-stock-card"><strong>{{card.cardName}}</strong><p>{{card.languageCode}} · {{agentStatusLabel(card.statusCode)}}</p><p v-if="card.gradingCertId">证号：{{card.gradingCertId}}</p><AgentPrivatePhoto :photo-id="card.frontPhotoId" label="正面" /><AgentPrivatePhoto :photo-id="card.backPhotoId" label="背面" /></article></div><el-button class="agent-spacing" :disabled="busy" @click="loadPackingSlip">查看收卡单信息</el-button><div v-if="packingSlip" class="agent-quote"><strong>{{packingSlip.orderNo}} · {{packingSlip.totalCardCount}} 张</strong><span>收卡码：{{packingSlip.intakeCode}}</span><span>装箱单：{{packingSlip.packingSlipCode}}</span><p v-for="line in packingSlip.packingInstructions" :key="line">{{line}}</p></div><h3>订单进度</h3><ol class="agent-timeline"><li v-for="event in order.timeline" :key="event.id"><div><strong>{{event.title}}</strong><time>{{agentDateLabel(event.createdAt)}}</time></div><p v-if="event.detail">{{event.detail}}</p></li></ol></section>
</section></template>
