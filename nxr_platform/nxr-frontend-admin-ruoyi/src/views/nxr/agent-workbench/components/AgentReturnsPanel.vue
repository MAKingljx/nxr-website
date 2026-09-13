<script setup lang="ts">
import { useAgentApi } from '../lib/agentWorkbench'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import PrivateOrderPhoto from './AgentPrivatePhoto.vue'
import {
  agentAddressLabel, agentDateLabel, agentStatusLabel, normalizeAgentReturnScan,
  emptyAgentAddress, emptyAgentPage, useAgentActions,
  type AgentCard, type AgentClient, type AgentShipment, type AgentShipmentDetail,
} from '../lib/agentWorkbench'
import AgentAddressFields from './AgentAddressFields.vue'
import AgentClientPicker from './AgentClientPicker.vue'
import AgentPagination from './AgentPagination.vue'
import AgentTimeline from './AgentTimeline.vue'

const api = useAgentApi()
const { fetchAgentCards, checkAgentReturn, fetchAgentShipments, fetchAgentShipment, createAgentShipment, deliverAgentShipment } = api

const props = defineProps<{ initialClientId?: number }>()
const shipments = ref(emptyAgentPage<AgentShipment>()), pendingCards = ref(emptyAgentPage<AgentCard>()), returnCards = ref(emptyAgentPage<AgentCard>())
const detail = ref<AgentShipmentDetail | null>(null), lastChecked = ref<AgentCard | null>(null)
const mode = ref<'check' | 'new' | 'detail'>('check'), query = ref(''), cardQuery = ref(''), clientFilter = ref(props.initialClientId || 0)
const shipmentLoading = ref(false), cardLoading = ref(false), detailLoading = ref(false), returnLoading = ref(false)
const scan = reactive({ inventoryCode: '', note: '' }), scanInput = ref<HTMLInputElement | null>(null), deliveryNote = ref('')
const form = reactive({ clientId: props.initialClientId || 0, carrierName: '', trackingNumber: '', note: '' })
const address = ref(emptyAgentAddress()), selectedClient = ref<AgentClient | null>(null), selected = ref<Record<number, AgentCard>>({})
const selectedCards = computed(() => Object.values(selected.value))
const { busy, error, success, run } = useAgentActions()
let shipmentGeneration = 0, cardGeneration = 0, detailGeneration = 0, returnGeneration = 0
async function loadShipments(page = 1) {
  const current = ++shipmentGeneration
  shipmentLoading.value = true
  try { const result = await fetchAgentShipments({ page, pageSize: 20, query: query.value.trim(), clientId: clientFilter.value || undefined }); if (current === shipmentGeneration) shipments.value = result }
  catch (e) { if (current === shipmentGeneration) error.value = e instanceof Error ? e.message : '回寄记录加载失败。' }
  finally { if (current === shipmentGeneration) shipmentLoading.value = false }
}
async function loadPending(page = 1) {
  const current = ++cardGeneration
  cardLoading.value = true
  try { const result = await fetchAgentCards({ page, pageSize: 20, query: cardQuery.value.trim(), clientId: clientFilter.value || undefined, statusCode: 'submitted' }); if (current === cardGeneration) pendingCards.value = result }
  catch (e) { if (current === cardGeneration) error.value = e instanceof Error ? e.message : '回卡列表加载失败。' }
  finally { if (current === cardGeneration) cardLoading.value = false }
}
async function loadReturnCards(page = 1) {
  const current = ++returnGeneration
  returnCards.value = emptyAgentPage()
  if (!form.clientId) return
  returnLoading.value = true
  try { const result = await fetchAgentCards({ page, pageSize: 50, clientId: form.clientId, statusCode: 'returned' }); if (current === returnGeneration) returnCards.value = result }
  catch (e) { if (current === returnGeneration) error.value = e instanceof Error ? e.message : '可回寄卡片加载失败。' }
  finally { if (current === returnGeneration) returnLoading.value = false }
}
watch(() => form.clientId, () => { selected.value = {}; selectedClient.value = null; address.value = emptyAgentAddress(); loadReturnCards() })
function chooseClient(client: AgentClient) {
  selectedClient.value = client
  address.value = { contactName: client.contactName || client.displayName, phone: client.phone || '', addressLine1: client.addressLine1 || '', addressLine2: client.addressLine2 || '', city: client.city || '', region: client.region || '', postalCode: client.postalCode || '', country: client.country || '' }
}
function startShipment() { detailGeneration++; detailLoading.value = false; mode.value = 'new'; error.value = ''; success.value = ''; loadReturnCards() }
async function show(id: number) {
  if (busy.value) return
  const current = ++detailGeneration
  detailLoading.value = true; mode.value = 'detail'; error.value = ''; success.value = ''; deliveryNote.value = ''
  try { const result = await fetchAgentShipment(id); if (current === detailGeneration) detail.value = result }
  catch (e) { if (current === detailGeneration) error.value = e instanceof Error ? e.message : '回寄详情加载失败。' }
  finally { if (current === detailGeneration) detailLoading.value = false }
}
async function checkReturn() {
  if (!scan.inventoryCode.trim()) return
  let inventoryCode = ''
  try { inventoryCode = normalizeAgentReturnScan(scan.inventoryCode) }
  catch (e) { error.value = e instanceof Error ? e.message : '库存码无法识别。'; return }
  const payload = { inventoryCode, note: scan.note.trim() }
  const saved = await run('card:return-check', payload, async key => {
    const found = await fetchAgentCards({ query: payload.inventoryCode, pageSize: 100, clientId: clientFilter.value || undefined })
    const card = found.items.find(item => item.inventoryCode.toUpperCase() === payload.inventoryCode.toUpperCase() || item.gradingCertId?.toUpperCase() === payload.inventoryCode.toUpperCase())
    if (!card) throw new Error('未找到此库存码或证号，请检查卡片或当前客户筛选。')
    if (!['submitted', 'returned'].includes(card.statusCode)) throw new Error(`此卡当前为“${agentStatusLabel(card.statusCode)}”，不能进行回卡核对。`)
    if (card.batchStatusCode !== 'delivered') throw new Error('NXR 返程批次尚未确认签收，请核实包裹并联系 NXR 更新批次签收状态。')
    return checkAgentReturn(card.id, payload, key)
  }, value => { lastChecked.value = value; scan.inventoryCode = ''; scan.note = '' }, '回卡已核对，可按客户装箱回寄。')
  if (saved) await loadPending(pendingCards.value.page)
  await nextTick(); scanInput.value?.focus(); if (!saved) scanInput.value?.select()
}
function selectCard(card: AgentCard, event: Event) {
  if ((event.target as HTMLInputElement).checked) selected.value[card.id] = card
  else delete selected.value[card.id]
}
async function createShipment() {
  if (!selectedCards.value.length || !form.clientId || !selectedClient.value) return
  if (selectedCards.value.some(card => card.clientId !== form.clientId || card.statusCode !== 'returned')) { error.value = '请选择同一客户已核对的卡片。'; return }
  const payload = { ...form, address: { ...address.value }, cardIds: selectedCards.value.map(card => card.id).sort((a, b) => a - b) }
  const saved = await run('shipment:create', payload, key => createAgentShipment(payload, key), value => {
    detail.value = value; mode.value = 'detail'; selected.value = {}; form.carrierName = ''; form.trackingNumber = ''; form.note = ''
  }, '客户回寄已登记。')
  if (saved) await loadShipments()
}
async function delivered() {
  const id = detail.value?.shipment.id
  if (!id) return
  const payload = { note: deliveryNote.value.trim() }
  const saved = await run(`shipment:${id}:delivered`, payload, key => deliverAgentShipment(id, payload, key), value => { detail.value = value; deliveryNote.value = '' }, '已确认客户签收。')
  if (saved) await loadShipments(shipments.value.page)
}
function clearFilter() { clientFilter.value = 0; loadShipments(); loadPending() }
onMounted(() => Promise.all([loadShipments(), loadPending()]))
</script>

<template>
  <div class="agent-panel" data-testid="agent-returns-panel"><p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="success" class="agent-success" role="status">{{ success }}</p><div class="agent-split">
    <aside class="agent-list-pane"><div class="agent-toolbar"><h2>客户回寄</h2><button type="button" class="btn-primary" :disabled="busy" data-testid="agent-new-return" @click="startShipment">新建回寄</button></div><button type="button" class="btn-secondary agent-full-width" :disabled="busy" data-testid="agent-show-return-check" @click="mode = 'check'; loadPending()">回卡核对</button><form class="portal-form agent-search" @submit.prevent="loadShipments()"><label>搜索回寄单<input v-model="query" placeholder="回寄单号或运单号" /></label><button class="btn-secondary" :disabled="shipmentLoading">搜索</button><button v-if="clientFilter" type="button" class="text-button" @click="clearFilter">取消客户筛选</button></form><p v-if="shipmentLoading" class="muted-copy" role="status">加载中…</p><p v-else-if="!shipments.items.length" class="portal-empty">暂无客户回寄。</p><div class="agent-record-list"><button v-for="shipment in shipments.items" :key="shipment.id" type="button" class="agent-record" :class="{ selected: detail?.shipment.id === shipment.id && mode === 'detail' }" :disabled="busy" @click="show(shipment.id)"><strong>{{ shipment.shipmentNo }}</strong><span>{{ shipment.clientName }} · {{ shipment.cardCount }} 张</span><span>{{ agentStatusLabel(shipment.statusCode) }}</span></button></div><AgentPagination v-bind="shipments" :disabled="shipmentLoading || busy" @change="loadShipments" /></aside>
    <section class="agent-detail-pane">
      <template v-if="mode === 'check'"><h2>回卡核对</h2><p class="muted-copy">NXR 返程批次确认签收后，逐卡扫描库存码或证号核对实物。</p><form class="portal-form agent-operation" @submit.prevent="checkReturn"><label>扫描或输入库存码 / 证号<input ref="scanInput" v-model="scan.inventoryCode" required autocomplete="off" autocapitalize="off" spellcheck="false" :disabled="busy" placeholder="扫描后按回车" data-testid="agent-return-check-code" /></label><label>核对备注（选填）<input v-model="scan.note" maxlength="2000" :disabled="busy" /></label><button class="btn-primary" :disabled="busy" data-testid="agent-return-check-submit">{{ busy ? '核对中…' : '确认回卡' }}</button></form><div v-if="lastChecked" class="agent-quote"><strong>{{ lastChecked.cardName }} · {{ lastChecked.clientName }}</strong><code>{{ lastChecked.inventoryCode }}</code><span>回卡已核对</span></div><form class="portal-form agent-search" @submit.prevent="loadPending()"><label>搜索待核对卡片<input v-model="cardQuery" placeholder="库存码、证号或卡名" /></label><button class="btn-secondary" :disabled="cardLoading">搜索</button></form><p v-if="cardLoading" class="muted-copy" role="status">加载中…</p><p v-else-if="!pendingCards.items.length" class="portal-empty">暂无待核对回卡。</p><div v-else class="table-scroll"><table class="portal-table"><thead><tr><th>库存码 / 卡片</th><th>客户</th><th>NXR 批次</th><th>核对条件</th></tr></thead><tbody><tr v-for="card in pendingCards.items" :key="card.id"><td><code>{{ card.inventoryCode }}</code><small>{{ card.cardName }} · {{ card.languageCode }}</small></td><td>{{ card.clientName }}</td><td><router-link :to="{ path: '/nxr/submission-workbench', query: { tab: 'batches', company: api.companyId } }">{{ card.batchNo }}</router-link></td><td>{{ card.batchStatusCode === 'delivered' ? '可扫码核对' : '等待子代理签收' }}</td></tr></tbody></table></div><AgentPagination v-bind="pendingCards" :disabled="cardLoading || busy" @change="loadPending" /></template>
      <form v-else-if="mode === 'new'" class="portal-form" data-testid="agent-return-form" @submit.prevent="createShipment"><div class="agent-toolbar"><h2>新建客户回寄</h2><button type="button" class="text-button" :disabled="busy" @click="mode = 'check'">取消</button></div><fieldset :disabled="busy"><AgentClientPicker v-model="form.clientId" :active-only="false" :disabled="busy" @selected="chooseClient" /><h3>选择已核对卡片 · 已选 {{ selectedCards.length }} 张</h3><p v-if="returnLoading" class="muted-copy" role="status">加载可回寄卡片…</p><p v-else-if="!returnCards.items.length" class="muted-copy">此客户暂无可回寄卡片，请先完成回卡核对。</p><label v-for="card in returnCards.items" :key="card.id" class="agent-card-choice"><input type="checkbox" :checked="Boolean(selected[card.id])" :data-testid="`agent-select-return-card-${card.id}`" @change="selectCard(card, $event)" /><span><strong>{{ card.cardName }}</strong><code>{{ card.inventoryCode }}</code><small>{{ card.intakeNo }} · {{ card.batchNo }}</small></span></label><AgentPagination v-bind="returnCards" :disabled="returnLoading || busy" @change="loadReturnCards" /><h3>客户收货地址</h3><AgentAddressFields v-model="address" /><p class="muted-copy">本次回寄保存此地址，后续修改客户档案不会改变此单。</p><div class="form-grid"><label>快递公司<input v-model="form.carrierName" required maxlength="128" data-testid="agent-return-carrier" /></label><label>运单号<input v-model="form.trackingNumber" required maxlength="255" data-testid="agent-return-tracking" /></label></div><label>回寄备注<textarea v-model="form.note" maxlength="2000" rows="2" /></label></fieldset><button class="btn-primary" :disabled="busy || returnLoading || !selectedCards.length || !selectedClient" data-testid="agent-create-return">{{ busy ? '登记中…' : `确认发出 ${selectedCards.length} 张卡片` }}</button></form>
      <p v-else-if="detailLoading" class="portal-empty" role="status">加载回寄详情…</p>
      <template v-else-if="detail"><div class="agent-toolbar"><div><h2>{{ detail.shipment.shipmentNo }}</h2><p class="muted-copy">{{ detail.shipment.clientName }} · {{ detail.shipment.cardCount }} 张卡片</p></div><span class="status-pill">{{ agentStatusLabel(detail.shipment.statusCode) }}</span></div><dl class="agent-facts"><div><dt>回寄物流</dt><dd>{{ detail.shipment.carrierName }} · {{ detail.shipment.trackingNumber }}</dd></div><div><dt>收件人</dt><dd>{{ detail.shipment.address.contactName }} · {{ detail.shipment.address.phone }}</dd></div><div><dt>收货地址快照</dt><dd>{{ agentAddressLabel(detail.shipment.address) }}</dd></div><div><dt>发出时间</dt><dd>{{ agentDateLabel(detail.shipment.shippedAt) }}</dd></div><div><dt>客户签收</dt><dd>{{ agentDateLabel(detail.shipment.deliveredAt) }}</dd></div><div v-if="detail.shipment.notes"><dt>备注</dt><dd>{{ detail.shipment.notes }}</dd></div></dl><form v-if="detail.shipment.statusCode !== 'delivered'" class="portal-form agent-operation" @submit.prevent="delivered"><label>签收备注（选填）<input v-model="deliveryNote" maxlength="2000" :disabled="busy" /></label><button class="btn-primary" :disabled="busy" data-testid="agent-confirm-client-delivery">确认客户已签收</button></form><h3>本次回寄卡片</h3><div class="agent-inventory-cards"><article v-for="card in detail.cards" :key="card.id" class="agent-stock-card"><div class="agent-toolbar"><strong>{{ card.cardName }}</strong><span class="status-pill">{{ agentStatusLabel(card.statusCode) }}</span></div><code class="agent-inventory-code">{{ card.inventoryCode }}</code><p class="muted-copy">{{ card.languageCode }} · {{ card.intakeNo }}</p><router-link v-if="card.orderNo" :to="{ path: '/nxr/submission-workbench', query: { tab: 'batches', order: card.orderNo, company: api.companyId } }">{{ card.orderNo }}</router-link><div><PrivateOrderPhoto :photo-id="card.frontPhotoId" label="正面" /><PrivateOrderPhoto :photo-id="card.backPhotoId" label="背面" /></div></article></div><AgentTimeline :events="detail.events" :scope="{ shipmentId: detail.shipment.id }" /></template>
    </section>
  </div></div>
</template>
