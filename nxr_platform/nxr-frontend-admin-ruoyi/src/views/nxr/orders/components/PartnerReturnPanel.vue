<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { activeLocale, tx, localizeBackendMessage } from '@/i18n'
import auth from '@/plugins/auth'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listPartnerReturnCompanies, listPartnerReturnCards, checkPartnerReturnCard, listPartnerReturns, getPartnerReturn, createPartnerReturn, deliverPartnerReturn } from '@/api/nxr/partnerReturns'
const emit = defineEmits(['open-order', 'changed'])
const canView = auth.hasPermiOr(['nxr:order:manage','nxr:order:shipping','nxr:order:batch','nxr:order:workbench'])
const canWrite = auth.hasPermiOr(['nxr:order:manage','nxr:order:shipping'])
const activeTab = ref('cards'), companies = ref([]), companyId = ref(), query = ref(''), page = ref(1), pageSize = ref(20), total = ref(0)
const rows = ref([]), loading = ref(false), loadError = ref(''), working = ref(false)
const selected = ref(new Map()), tableRef = ref()
const checkOpen = ref(false), checkCard = ref(null), checkForm = ref({ certId:'', qualityPassed:false, packingPassed:false, note:'' }), checkError = ref('')
const createOpen = ref(false), createForm = ref({carrierName:'',trackingNumber:'',note:''}), createError = ref('')
const detailOpen = ref(false), detail = ref(null)
let generation = 0, detailGeneration = 0, disposed = false, attempt = null
onBeforeUnmount(() => {disposed = true; generation++; detailGeneration++})
const selectedCards = computed(() => [...selected.value.values()])
const selectedOrders = computed(() => new Set(selectedCards.value.map(c => c.orderId)).size)
const selectedSubmits = computed(() => new Set(selectedCards.value.map(c => c.batchId)).size)
function addressText(address) {
  if (!address) return '—'
  if (typeof address === 'string') return address
  return [address.contactName,address.phone || address.contactPhone,address.addressLine1,address.addressLine2,address.city,address.region,address.postalCode,address.country].filter(Boolean).join(' · ')
}
const selectionCompatible = computed(() => new Set(selectedCards.value.map(c => String(c.companyId)+'|'+addressText(c.returnAddress))).size <= 1)
function date(value) {return value ? new Date(value).toLocaleString(activeLocale()) : '—'}
function selectionChanged(value) {
  const next = new Map(selected.value)
  rows.value.forEach(row => next.delete(row.orderItemId))
  value.forEach(row => next.set(row.orderItemId,row))
  selected.value = next
}
function clearSelection() {selected.value = new Map(); tableRef.value?.clearSelection()}
async function load(reset = false) {
  if (!canView) return
  if (reset) page.value = 1
  const current = ++generation
  loading.value = true; loadError.value = ''
  try {
    const params = {companyId:companyId.value,query:query.value,page:page.value,pageSize:pageSize.value}
    const result = await (activeTab.value === 'cards' ? listPartnerReturnCards(params) : listPartnerReturns(params))
    if (disposed || current !== generation) return
    rows.value = result.data.items || []; total.value = result.data.total || 0
  } catch(e) {if(!disposed && current === generation){rows.value=[];total.value=0;loadError.value=e.message || tx('Unable to load partner returns.')}}
  finally {if(!disposed && current === generation)loading.value=false}
}
function changeFilter() {clearSelection(); load(true)}
function changeTab() {clearSelection(); rows.value=[]; load(true)}
async function loadCompanies() {
  try {const result=await listPartnerReturnCompanies({});if(!disposed)companies.value=Array.isArray(result.data)?result.data:result.data?.items||[]}
  catch(e) {if(!disposed)loadError.value=e.message||tx('Unable to load partner companies.')}
}
function openCheck(card) {
  if (!canWrite || working.value) return
  checkCard.value = card
  checkForm.value = {certId:'',qualityPassed:false,packingPassed:false,note:''}
  checkError.value='';checkOpen.value=true
}
async function saveCheck() {
  if (!checkCard.value || !checkForm.value.certId.trim() || !checkForm.value.qualityPassed || !checkForm.value.packingPassed || working.value) return
  working.value=true;checkError.value=''
  try {
    await checkPartnerReturnCard(checkCard.value.orderItemId,{...checkForm.value,certId:checkForm.value.certId.trim()})
    if(disposed)return
    checkOpen.value=false;clearSelection();await load();ElMessage.success(tx('Card return checks saved.'))
  } catch(e){if(!disposed)checkError.value=e.message||tx('Card checks were not saved.')}
  finally {if(!disposed)working.value=false}
}
function openCreate() {if(!selectedCards.value.length || !selectionCompatible.value)return; createForm.value={carrierName:'',trackingNumber:'',note:''};createError.value='';createOpen.value=true}
async function submitParcel() {
  if(working.value || !selectionCompatible.value || !selectedCards.value.length)return
  if(!createForm.value.carrierName.trim() || !createForm.value.trackingNumber.trim()){createError.value=tx('Enter a carrier and tracking number.');return}
  const body={orderItemIds:selectedCards.value.map(c=>c.orderItemId).sort((a,b)=>a-b),carrierName:createForm.value.carrierName.trim(),trackingNumber:createForm.value.trackingNumber.trim(),note:createForm.value.note.trim()}
  const fingerprint=JSON.stringify(body)
  if(!attempt || attempt.fingerprint!==fingerprint)attempt={fingerprint,key:crypto.randomUUID()}
  working.value=true;createError.value=''
  try {
    const result=await createPartnerReturn({...body,requestKey:attempt.key})
    if(disposed)return
    attempt=null;createOpen.value=false;clearSelection();detail.value=result.data;detailOpen.value=true
    await load();emit('changed');ElMessage.success(tx('Return parcel created.'))
  } catch(e){if(!disposed)createError.value=e.message||tx('Return parcel was not created.')}
  finally {if(!disposed)working.value=false}
}
async function openParcel(row) {
  const current=++detailGeneration;loadError.value=''
  try {const result=await getPartnerReturn(row.id);if(!disposed&&current===detailGeneration){detail.value=result.data;detailOpen.value=true}}
  catch(e){if(!disposed&&current===detailGeneration)loadError.value=e.message||tx('Unable to load return parcel.')}
}
async function delivered() {
  if(!detail.value || working.value)return
  try {await ElMessageBox.confirm(tx('Confirm that B has received every card in this parcel.'),tx('Confirm delivery to B'),{type:'warning'})}catch{return}
  working.value=true
  try{const result=await deliverPartnerReturn(detail.value.id);if(disposed)return;detail.value=result.data;await load();emit('changed');ElMessage.success(tx('Delivery to B confirmed.'))}
  finally{if(!disposed)working.value=false}
}
if(canView){loadCompanies();load()}
</script>

<template>
  <section class="partner-returns" v-if="canView" data-testid="partner-return-panel">
    <div class="return-title"><div><h2>{{ $tx('Return to B') }}</h2></div><el-button v-if="canWrite" type="primary" icon="Plus" :disabled="!selectedCards.length || !selectionCompatible || working" @click="openCreate">{{ $tx('Create return parcel') }}</el-button></div>
    <el-tabs v-model="activeTab" @tab-change="changeTab"><el-tab-pane :label="$tx('Return card list')" name="cards" /><el-tab-pane :label="$tx('Return parcels')" name="parcels" /></el-tabs>
    <el-form inline class="return-filters" @submit.prevent="load(true)">
      <el-form-item :label="$tx('Partner company')"><el-select v-model="companyId" filterable clearable :placeholder="$tx('All partner companies')" @change="changeFilter"><el-option v-for="company in companies" :key="company.id" :value="company.id" :label="company.displayName || company.companyName" /></el-select></el-form-item>
      <el-form-item :label="$tx('Search')"><el-input v-model="query" clearable :placeholder="$tx('Order, certificate, card or parcel')" /></el-form-item>
      <el-form-item><el-button native-type="submit" type="primary" :loading="loading">{{ $tx('Search') }}</el-button><el-button icon="Refresh" :disabled="working" @click="load()">{{ $tx('Refresh') }}</el-button></el-form-item>
    </el-form>
    <el-alert v-if="loadError" :title="localizeBackendMessage(loadError)" type="error" :closable="false" class="mb12" />
    <template v-if="activeTab==='cards'">
      <el-table ref="tableRef" :data="rows" row-key="orderItemId" v-loading="loading" @selection-change="selectionChanged">
        <el-table-column v-if="canWrite" type="selection" width="48" reserve-selection :selectable="row=>Boolean(row.eligible) && !working" />
        <el-table-column :label="$tx('Order')" min-width="155"><template #default="{row}"><el-button type="primary" link @click="emit('open-order',row.orderId)">{{row.orderReference || row.orderNo}}</el-button><small>{{row.batchNo}}</small></template></el-table-column>
        <el-table-column :label="$tx('Partner company')" prop="companyName" min-width="145" show-overflow-tooltip />
        <el-table-column :label="$tx('Card Name')" prop="cardName" min-width="170" />
        <el-table-column :label="$tx('Certificate ID')" prop="certId" min-width="125" />
        <el-table-column :label="$tx('Return readiness')" min-width="235"><template #default="{row}"><el-tag :type="row.eligible?'success':row.checked?'warning':'info'">{{['return_shipped','delivered'].includes(row.itemStatus)?$tx(row.itemStatus==='delivered'?'Delivered to B':'Returning to B'):row.eligible?$tx('Ready to return'):$tx('Not cleared')}}</el-tag><small v-if="row.blockedReason && !['return_shipped','delivered'].includes(row.itemStatus)">{{localizeBackendMessage(row.blockedReason)}}</small></template></el-table-column>
        <el-table-column :label="$tx('Actions')" width="150"><template #default="{row}"><el-button v-if="canWrite && !['return_shipped','delivered','cancelled'].includes(row.itemStatus)" type="primary" link :disabled="working || !row.certId" @click="openCheck(row)">{{$tx('Check card')}}</el-button><el-button link :disabled="working" @click="emit('open-order',row.orderId)">{{$tx('Grading')}}</el-button></template></el-table-column>
      </el-table>
      <div v-if="selectedCards.length" class="selection-bar"><strong>{{$tx('{p1} cards from {p2} orders and {p3} submits',{p1:selectedCards.length,p2:selectedOrders,p3:selectedSubmits})}}</strong><el-button link @click="clearSelection">{{$tx('Clear selection')}}</el-button><el-alert v-if="!selectionCompatible" :title="$tx('Select cards for the same B partner and return address.')" type="warning" :closable="false" /></div>
    </template>
    <el-table v-else :data="rows" v-loading="loading">
      <el-table-column :label="$tx('Return parcel')" prop="parcelNo" min-width="155" />
      <el-table-column :label="$tx('Partner company')" prop="companyName" min-width="155" />
      <el-table-column :label="$tx('Cards')" prop="cardCount" width="80" />
      <el-table-column :label="$tx('Carrier')" prop="carrierName" min-width="120" />
      <el-table-column :label="$tx('Tracking number')" prop="trackingNumber" min-width="175" />
      <el-table-column :label="$tx('Status')" min-width="140"><template #default="{row}"><el-tag :type="row.deliveredAt?'success':'primary'">{{row.deliveredAt?$tx('Delivered to B'):$tx('Returning to B')}}</el-tag></template></el-table-column>
      <el-table-column :label="$tx('Shipped At')" min-width="170"><template #default="{row}">{{date(row.shippedAt)}}</template></el-table-column>
      <el-table-column :label="$tx('Actions')" width="100"><template #default="{row}"><el-button link type="primary" @click="openParcel(row)">{{$tx('View')}}</el-button></template></el-table-column>
    </el-table>
    <pagination v-show="total>0" :total="total" v-model:page="page" v-model:limit="pageSize" @pagination="load()" />

    <el-drawer v-model="checkOpen" :title="$tx('Card return checks')" size="min(650px,100vw)" append-to-body :close-on-click-modal="!working" :close-on-press-escape="!working" :show-close="!working">
      <template v-if="checkCard"><h3>{{checkCard.cardName}}</h3><p>{{checkCard.orderReference || checkCard.orderNo}} · {{checkCard.batchNo}}</p>
        <el-alert v-if="checkCard.blockedReason" :title="localizeBackendMessage(checkCard.blockedReason)" type="info" :closable="false" class="mb12" />
        <div class="return-images"><figure v-if="checkCard.frontImageUrl"><el-image :src="checkCard.frontImageUrl" :preview-src-list="[checkCard.frontImageUrl]" fit="contain" preview-teleported /><figcaption>{{$tx('Front')}}</figcaption></figure><figure v-if="checkCard.backImageUrl"><el-image :src="checkCard.backImageUrl" :preview-src-list="[checkCard.backImageUrl]" fit="contain" preview-teleported /><figcaption>{{$tx('Back')}}</figcaption></figure></div>
        <el-form label-position="top" :disabled="working" @submit.prevent="saveCheck"><el-form-item :label="$tx('Scan or enter the physical certificate number')" required><el-input v-model="checkForm.certId" maxlength="128" /></el-form-item><el-form-item><el-checkbox v-model="checkForm.qualityPassed">{{$tx('Final quality check passed for this card')}}</el-checkbox></el-form-item><el-form-item><el-checkbox v-model="checkForm.packingPassed">{{$tx('Card, label and protective packing match')}}</el-checkbox></el-form-item><el-form-item :label="$tx('Note')"><el-input v-model="checkForm.note" type="textarea" maxlength="2000" /></el-form-item><el-alert v-if="checkError" :title="localizeBackendMessage(checkError)" type="error" :closable="false" /></el-form>
      </template>
      <template #footer><el-button type="primary" :loading="working" :disabled="!checkForm.certId.trim() || !checkForm.qualityPassed || !checkForm.packingPassed" @click="saveCheck">{{$tx('Save card checks')}}</el-button></template>
    </el-drawer>
    <el-drawer v-model="createOpen" :title="$tx('Create return parcel')" size="min(950px,100vw)" append-to-body :close-on-click-modal="!working" :close-on-press-escape="!working" :show-close="!working">
      <div class="selection-summary"><strong>{{$tx('{p1} cards from {p2} orders and {p3} submits',{p1:selectedCards.length,p2:selectedOrders,p3:selectedSubmits})}}</strong><p>{{addressText(selectedCards[0]?.returnAddress)}}</p></div>
      <el-table :data="selectedCards" size="small"><el-table-column :label="$tx('Order')"><template #default="{row}">{{row.orderReference || row.orderNo}}</template></el-table-column><el-table-column :label="$tx('Submit')" prop="batchNo" /><el-table-column :label="$tx('Card Name')" prop="cardName" /><el-table-column :label="$tx('Certificate ID')" prop="certId" /></el-table>
      <el-form label-position="top" :disabled="working" class="create-fields" @submit.prevent="submitParcel"><div class="parcel-fields"><el-form-item :label="$tx('Carrier')" required><el-input v-model="createForm.carrierName" maxlength="128" /></el-form-item><el-form-item :label="$tx('Tracking number')" required><el-input v-model="createForm.trackingNumber" maxlength="255" /></el-form-item></div><el-form-item :label="$tx('Note')"><el-input type="textarea" v-model="createForm.note" maxlength="2000" /></el-form-item></el-form>
      <el-alert v-if="createError" :title="localizeBackendMessage(createError)" type="error" :closable="false" />
      <template #footer><el-button type="primary" :loading="working" :disabled="!selectionCompatible || !selectedCards.length" @click="submitParcel">{{$tx('Create and dispatch')}}</el-button></template>
    </el-drawer>
    <el-drawer v-model="detailOpen" :title="detail?.parcelNo || $tx('Return parcel')" size="min(1000px,100vw)" append-to-body>
      <template v-if="detail"><el-descriptions :column="2" border><el-descriptions-item :label="$tx('Partner company')">{{detail.companyName}}</el-descriptions-item><el-descriptions-item :label="$tx('Status')">{{detail.deliveredAt?$tx('Delivered to B'):$tx('Returning to B')}}</el-descriptions-item><el-descriptions-item :label="$tx('Carrier')">{{detail.carrierName}}</el-descriptions-item><el-descriptions-item :label="$tx('Tracking number')">{{detail.trackingNumber}}</el-descriptions-item><el-descriptions-item :label="$tx('Return address')" :span="2">{{addressText(detail.returnAddress)}}</el-descriptions-item></el-descriptions>
        <h3>{{$tx('Cards in this parcel')}}</h3><el-table :data="detail.items" size="small"><el-table-column :label="$tx('Order')" min-width="150"><template #default="{row}"><el-button type="primary" link @click="detailOpen=false;emit('open-order',row.orderId)">{{row.orderReference || row.orderNo}}</el-button></template></el-table-column><el-table-column :label="$tx('Submit')" prop="batchNo" min-width="170" /><el-table-column :label="$tx('Card Name')" prop="cardName" min-width="180" /><el-table-column :label="$tx('Certificate ID')" prop="certId" min-width="130" /></el-table>
        <h3>{{$tx('Parcel progress')}}</h3><el-steps :active="detail.deliveredAt?2:1" finish-status="success"><el-step :title="$tx('Dispatched by NXR')" :description="date(detail.shippedAt)" /><el-step :title="$tx('Delivered to B')" :description="detail.deliveredAt?date(detail.deliveredAt):$tx('Awaiting delivery')" /></el-steps>
      </template>
      <template #footer><el-button v-if="canWrite && detail && !detail.deliveredAt" type="primary" :loading="working" @click="delivered">{{$tx('Confirm delivery to B')}}</el-button></template>
    </el-drawer>
  </section>
</template>
<style scoped>
.partner-returns{min-width:0}.return-title{display:flex;justify-content:space-between;align-items:center;gap:16px;margin:0 0 12px}.return-title h2{margin:0;font-size:21px}.return-filters{display:flex;flex-wrap:wrap;gap:0 12px}.return-filters :deep(.el-select),.return-filters :deep(.el-input){width:240px;max-width:100%}.partner-returns :deep(td small){display:block;margin-top:6px;color:var(--nxr-text-muted);line-height:1.6}.selection-bar{display:flex;flex-wrap:wrap;align-items:center;gap:14px;margin-top:16px;padding:16px;border-radius:8px;background:var(--el-color-primary-light-9)}.selection-summary{padding:18px;margin-bottom:20px;border:1px solid var(--el-border-color);border-radius:8px}.create-fields{margin-top:24px}.parcel-fields{display:grid;grid-template-columns:1fr 1fr;gap:20px}.return-images{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin:20px 0}.return-images figure{margin:0;text-align:center}.return-images :deep(.el-image){width:100%;height:240px}.return-images figcaption{margin-top:8px;color:var(--nxr-text-muted)}h3{margin:24px 0 16px}@media(max-width:760px){.return-title{align-items:flex-start;flex-wrap:wrap}.parcel-fields{grid-template-columns:1fr}.return-filters :deep(.el-form-item){width:100%;margin-right:0}.return-filters :deep(.el-input),.return-filters :deep(.el-select){width:100%}}
</style>
