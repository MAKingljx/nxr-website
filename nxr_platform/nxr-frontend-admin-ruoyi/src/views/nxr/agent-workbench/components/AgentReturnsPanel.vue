<script setup lang="ts">
import { tx } from '@/i18n'
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

const props = defineProps<{ initialClientId?: number; initialRecordId?: number }>()
const shipments = ref(emptyAgentPage<AgentShipment>()), pendingCards = ref(emptyAgentPage<AgentCard>()), returnCards = ref(emptyAgentPage<AgentCard>())
const detail = ref<AgentShipmentDetail | null>(null), lastChecked = ref<AgentCard | null>(null)
const mode = ref<'list' | 'check' | 'new' | 'detail'>('list'), query = ref(''), cardQuery = ref(''), clientFilter = ref(props.initialClientId || 0)
const detailSection = ref<HTMLElement | null>(null), searchInput = ref<HTMLInputElement | null>(null)
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
  catch (e) { if (current === shipmentGeneration) error.value = e instanceof Error ? e.message : tx('Unable to load return shipment history.') }
  finally { if (current === shipmentGeneration) shipmentLoading.value = false }
}
async function loadPending(page = 1) {
  const current = ++cardGeneration
  cardLoading.value = true
  try { const result = await fetchAgentCards({ page, pageSize: 20, query: cardQuery.value.trim(), clientId: clientFilter.value || undefined, statusCode: 'submitted' }); if (current === cardGeneration) pendingCards.value = result }
  catch (e) { if (current === cardGeneration) error.value = e instanceof Error ? e.message : tx('Unable to load returned cards.') }
  finally { if (current === cardGeneration) cardLoading.value = false }
}
async function loadReturnCards(page = 1) {
  const current = ++returnGeneration
  returnCards.value = emptyAgentPage()
  if (!form.clientId) { returnLoading.value = false; return }
  returnLoading.value = true
  try { const result = await fetchAgentCards({ page, pageSize: 50, clientId: form.clientId, statusCode: 'returned' }); if (current === returnGeneration) returnCards.value = result }
  catch (e) { if (current === returnGeneration) error.value = e instanceof Error ? e.message : tx('Unable to load cards available for return.') }
  finally { if (current === returnGeneration) returnLoading.value = false }
}
watch(() => form.clientId, () => { selected.value = {}; selectedClient.value = null; address.value = emptyAgentAddress(); loadReturnCards() })
function chooseClient(client: AgentClient) {
  const sameClient = selectedClient.value?.id === client.id
  selectedClient.value = client
  // Reopening a draft remounts the picker; preserve its edited delivery address.
  if (sameClient) return
  address.value = { contactName: client.contactName || client.displayName, phone: client.phone || '', addressLine1: client.addressLine1 || '', addressLine2: client.addressLine2 || '', city: client.city || '', region: client.region || '', postalCode: client.postalCode || '', country: client.country || '' }
}
async function focusOperation() {
  await nextTick()
  detailSection.value?.focus({ preventScroll: true })
  detailSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
async function startShipment() {
  if (busy.value) return
  detailGeneration++; detailLoading.value = false; mode.value = 'new'; error.value = ''; success.value = ''
  loadReturnCards()
  await focusOperation()
}
async function startCheck() {
  if (busy.value) return
  detailGeneration++; detailLoading.value = false; mode.value = 'check'; error.value = ''; success.value = ''
  loadPending()
  await focusOperation()
  scanInput.value?.focus({ preventScroll: true })
}
async function closeOperation() {
  if (busy.value) return
  detailGeneration++; detailLoading.value = false; mode.value = 'list'
  await nextTick()
  searchInput.value?.focus({ preventScroll: true })
  searchInput.value?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}
async function show(id: number) {
  if (busy.value) return
  const current = ++detailGeneration
  if (detail.value?.shipment.id !== id) { detail.value = null; deliveryNote.value = '' }
  detailLoading.value = true; mode.value = 'detail'; error.value = ''; success.value = ''
  await focusOperation()
  try { const result = await fetchAgentShipment(id); if (current === detailGeneration) detail.value = result }
  catch (e) { if (current === detailGeneration) error.value = e instanceof Error ? e.message : tx('Unable to load return shipment details.') }
  finally { if (current === detailGeneration) detailLoading.value = false }
}
async function checkReturn() {
  if (!scan.inventoryCode.trim()) return
  let inventoryCode = ''
  try { inventoryCode = normalizeAgentReturnScan(scan.inventoryCode) }
  catch (e) { error.value = e instanceof Error ? e.message : tx('Inventory code not recognized.'); return }
  const payload = { inventoryCode, note: scan.note.trim() }
  const saved = await run('card:return-check', payload, async key => {
    const found = await fetchAgentCards({ query: payload.inventoryCode, pageSize: 100, clientId: clientFilter.value || undefined })
    const card = found.items.find(item => item.inventoryCode.toUpperCase() === payload.inventoryCode.toUpperCase() || item.gradingCertId?.toUpperCase() === payload.inventoryCode.toUpperCase())
    if (!card) throw new Error(tx('No matching inventory code or certificate ID. Check the card or the current customer filter.'))
    if (!['submitted', 'returned'].includes(card.statusCode)) throw new Error(tx('This card is currently {p1} and cannot be checked as returned.', { p1: agentStatusLabel(card.statusCode) }))
    if (card.batchStatusCode !== 'delivered') throw new Error(tx('Receipt of the NXR return batch has not been confirmed. Check the parcel and contact NXR to update its delivery status.'))
    return checkAgentReturn(card.id, payload, key)
  }, value => { lastChecked.value = value; scan.inventoryCode = ''; scan.note = '' }, tx('Returned card checked. It can now be packed and shipped to its customer.'))
  if (saved) await loadPending(pendingCards.value.page)
  await nextTick(); scanInput.value?.focus(); if (!saved) scanInput.value?.select()
}
function selectCard(card: AgentCard, event: Event) {
  if ((event.target as HTMLInputElement).checked) selected.value[card.id] = card
  else delete selected.value[card.id]
}
async function createShipment() {
  if (!selectedCards.value.length || !form.clientId || !selectedClient.value) return
  if (selectedCards.value.some(card => card.clientId !== form.clientId || card.statusCode !== 'returned')) { error.value = tx('Select checked cards belonging to the same customer.'); return }
  const payload = { ...form, address: { ...address.value }, cardIds: selectedCards.value.map(card => card.id).sort((a, b) => a - b) }
  const saved = await run('shipment:create', payload, key => createAgentShipment(payload, key), value => {
    detail.value = value; mode.value = 'detail'; selected.value = {}; form.carrierName = ''; form.trackingNumber = ''; form.note = ''
  }, tx('Customer return shipment recorded.'))
  if (saved) { await loadShipments(); await focusOperation() }
}
async function delivered() {
  const id = detail.value?.shipment.id
  if (!id) return
  const payload = { note: deliveryNote.value.trim() }
  const saved = await run(`shipment:${id}:delivered`, payload, key => deliverAgentShipment(id, payload, key), value => { detail.value = value; deliveryNote.value = '' }, tx('Customer delivery confirmed.'))
  if (saved) await loadShipments(shipments.value.page)
}
function clearFilter() { if (busy.value) return; clientFilter.value = 0; loadShipments(); if (mode.value === 'check') loadPending() }
onMounted(async () => { const initialGeneration = detailGeneration; await loadShipments(); if (props.initialRecordId && initialGeneration === detailGeneration) await show(props.initialRecordId) })
</script>

<template>
  <div class="agent-panel agent-list-layout" data-testid="agent-returns-panel">
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="success" class="agent-success" role="status">{{ success }}</p>

    <form class="portal-form agent-list-filters" data-testid="agent-return-filters" @submit.prevent="loadShipments()">
      <label class="agent-filter-field agent-filter-grow">
        {{ $tx('Search return shipments') }}
        <input ref="searchInput" v-model="query" :placeholder="$tx('Return shipment or tracking number')" :disabled="busy" data-testid="agent-return-search" />
      </label>
      <div class="agent-list-actions">
        <button type="submit" class="btn-secondary" :disabled="shipmentLoading || busy">{{ $tx('Search') }}</button>
        <button v-if="clientFilter" type="button" class="text-button" :disabled="busy" @click="clearFilter">{{ $tx('Clear customer filter') }}</button>
        <button type="button" class="btn-secondary" :disabled="busy" data-testid="agent-show-return-check" @click="startCheck">{{ $tx('Check returned cards') }}</button>
        <button type="button" class="btn-primary" :disabled="busy" data-testid="agent-new-return" @click="startShipment">{{ $tx('Create return shipment') }}</button>
      </div>
    </form>

    <div class="table-scroll agent-table-scroll" :aria-busy="shipmentLoading">
      <table class="portal-table agent-results-table" data-testid="agent-return-table" :aria-label="$tx('Customer returns')">
        <thead>
          <tr>
            <th scope="col">{{ $tx('Return shipment') }}</th>
            <th scope="col">{{ $tx('Customer') }}</th>
            <th scope="col">{{ $tx('Carrier') }}</th>
            <th scope="col">{{ $tx('Tracking number') }}</th>
            <th scope="col">{{ $tx('Cards') }}</th>
            <th scope="col">{{ $tx('Status') }}</th>
            <th scope="col">{{ $tx('Shipped at') }}</th>
            <th scope="col">{{ $tx('Actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="shipmentLoading"><td colspan="8" class="portal-empty" role="status">{{ $tx('Loading…') }}</td></tr>
          <tr v-else-if="!shipments.items.length"><td colspan="8" class="portal-empty">{{ $tx('No customer returns yet.') }}</td></tr>
          <tr v-for="shipment in shipmentLoading ? [] : shipments.items" :key="shipment.id" :class="{ 'agent-row-selected': detail?.shipment.id === shipment.id && mode === 'detail' }" :data-testid="`agent-return-row-${shipment.id}`">
            <td><strong>{{ shipment.shipmentNo }}</strong></td>
            <td>{{ shipment.clientName }}</td>
            <td>{{ shipment.carrierName || '—' }}</td>
            <td><code>{{ shipment.trackingNumber || '—' }}</code></td>
            <td>{{ shipment.cardCount }}</td>
            <td><span class="status-pill">{{ agentStatusLabel(shipment.statusCode) }}</span></td>
            <td>{{ agentDateLabel(shipment.shippedAt) }}</td>
            <td><button type="button" class="text-button" :disabled="busy" :aria-label="$tx('View details for {name}', { name: shipment.shipmentNo })" :data-testid="`agent-return-view-${shipment.id}`" @click="show(shipment.id)">{{ $tx('View') }}</button></td>
          </tr>
        </tbody>
      </table>
    </div>
    <AgentPagination v-bind="shipments" :disabled="shipmentLoading || busy" @change="loadShipments" />

    <section v-if="mode !== 'list'" ref="detailSection" class="agent-detail-section" tabindex="-1" aria-labelledby="agent-return-operation-heading" data-testid="agent-return-operation">
      <div class="agent-toolbar">
        <h2 id="agent-return-operation-heading">{{ mode === 'check' ? $tx('Check returned cards') : mode === 'new' ? $tx('Create customer return') : detail?.shipment.shipmentNo || $tx('Return shipment') }}</h2>
        <button type="button" class="text-button" :disabled="busy" data-testid="agent-return-close" @click="closeOperation">{{ mode === 'new' ? $tx('Cancel') : $tx('Close') }}</button>
      </div>

      <template v-if="mode === 'check'">
        <p class="muted-copy">{{ $tx('Once receipt of the NXR return batch is confirmed, scan each inventory code or certificate ID to check the physical cards.') }}</p>
        <form class="portal-form agent-operation" @submit.prevent="checkReturn">
          <label>{{ $tx('Scan or enter inventory code / certificate ID') }}<input ref="scanInput" v-model="scan.inventoryCode" required autocomplete="off" autocapitalize="off" spellcheck="false" :disabled="busy" :placeholder="$tx('Scan, then press Enter')" data-testid="agent-return-check-code" /></label>
          <label>{{ $tx('Check notes (optional)') }}<input v-model="scan.note" maxlength="2000" :disabled="busy" /></label>
          <button class="btn-primary" :disabled="busy" data-testid="agent-return-check-submit">{{ busy ? $tx('Checking…') : $tx('Confirm returned card') }}</button>
        </form>
        <div v-if="lastChecked" class="agent-quote"><strong>{{ lastChecked.cardName }} · {{ lastChecked.clientName }}</strong><code>{{ lastChecked.inventoryCode }}</code><span>{{ $tx('Returned card checked') }}</span></div>
        <form class="portal-form agent-list-filters" @submit.prevent="loadPending()">
          <label class="agent-filter-field agent-filter-grow">{{ $tx('Search cards awaiting check') }}<input v-model="cardQuery" :placeholder="$tx('Inventory code, certificate ID or card name')" :disabled="busy" /></label>
          <div class="agent-list-actions"><button class="btn-secondary" :disabled="cardLoading || busy">{{ $tx('Search') }}</button></div>
        </form>
        <p v-if="cardLoading" class="muted-copy" role="status">{{ $tx('Loading…') }}</p>
        <p v-else-if="!pendingCards.items.length" class="portal-empty">{{ $tx('No returned cards awaiting check.') }}</p>
        <div v-else class="table-scroll">
          <table class="portal-table">
            <thead><tr><th scope="col">{{ $tx('Inventory code / card') }}</th><th scope="col">{{ $tx('Customer') }}</th><th scope="col">{{ $tx('NXR batch') }}</th><th scope="col">{{ $tx('Check eligibility') }}</th></tr></thead>
            <tbody><tr v-for="card in pendingCards.items" :key="card.id"><td><code>{{ card.inventoryCode }}</code><small>{{ card.cardName }} · {{ card.languageCode }}</small></td><td>{{ card.clientName }}</td><td><router-link :to="{ path: '/nxr/submission-workbench', query: { tab: 'batches', company: api.companyId } }">{{ card.batchNo }}</router-link></td><td>{{ card.batchStatusCode === 'delivered' ? $tx('Ready to scan') : $tx('Awaiting sub-agent receipt') }}</td></tr></tbody>
          </table>
        </div>
        <AgentPagination v-bind="pendingCards" :disabled="cardLoading || busy" @change="loadPending" />
      </template>

      <form v-else-if="mode === 'new'" class="portal-form" data-testid="agent-return-form" @submit.prevent="createShipment">
        <fieldset :disabled="busy">
          <AgentClientPicker v-model="form.clientId" :active-only="false" :disabled="busy" @selected="chooseClient" />
          <h3>{{ $tx('Select checked cards · {p1} selected', { p1: selectedCards.length }) }}</h3>
          <p v-if="returnLoading" class="muted-copy" role="status">{{ $tx('Loading cards available for return…') }}</p>
          <p v-else-if="!returnCards.items.length" class="muted-copy">{{ $tx('No cards are available to return to this customer. Check the returned cards first.') }}</p>
          <label v-for="card in returnCards.items" :key="card.id" class="agent-card-choice"><input type="checkbox" :checked="Boolean(selected[card.id])" :data-testid="`agent-select-return-card-${card.id}`" @change="selectCard(card, $event)" /><span><strong>{{ card.cardName }}</strong><code>{{ card.inventoryCode }}</code><small>{{ card.intakeNo }} · {{ card.batchNo }}</small></span></label>
          <AgentPagination v-bind="returnCards" :disabled="returnLoading || busy" @change="loadReturnCards" />
          <h3>{{ $tx('Customer delivery address') }}</h3>
          <AgentAddressFields v-model="address" />
          <p class="muted-copy">{{ $tx('This address is saved with the return shipment. Later customer record changes will not alter this shipment.') }}</p>
          <div class="form-grid"><label>{{ $tx('Carrier') }}<input v-model="form.carrierName" required maxlength="128" data-testid="agent-return-carrier" /></label><label>{{ $tx('Tracking number') }}<input v-model="form.trackingNumber" required maxlength="255" data-testid="agent-return-tracking" /></label></div>
          <label>{{ $tx('Return notes') }}<textarea v-model="form.note" maxlength="2000" rows="2" /></label>
        </fieldset>
        <button class="btn-primary" :disabled="busy || returnLoading || !selectedCards.length || !selectedClient" data-testid="agent-create-return">{{ busy ? $tx('Recording…') : $tx('Confirm shipment of {p1} cards', { p1: selectedCards.length }) }}</button>
      </form>

      <p v-else-if="detailLoading" class="portal-empty" role="status">{{ $tx('Loading return shipment details…') }}</p>
      <template v-else-if="detail">
        <div class="agent-toolbar"><p class="muted-copy">{{ $tx('{p1} · {p2} cards', { p1: detail.shipment.clientName, p2: detail.shipment.cardCount }) }}</p><span class="status-pill">{{ agentStatusLabel(detail.shipment.statusCode) }}</span></div>
        <dl class="agent-facts">
          <div><dt>{{ $tx('Return tracking') }}</dt><dd>{{ detail.shipment.carrierName }} · {{ detail.shipment.trackingNumber }}</dd></div>
          <div><dt>{{ $tx('Recipient') }}</dt><dd>{{ detail.shipment.address.contactName }} · {{ detail.shipment.address.phone }}</dd></div>
          <div><dt>{{ $tx('Delivery address snapshot') }}</dt><dd>{{ agentAddressLabel(detail.shipment.address) }}</dd></div>
          <div><dt>{{ $tx('Shipped at') }}</dt><dd>{{ agentDateLabel(detail.shipment.shippedAt) }}</dd></div>
          <div><dt>{{ $tx('Customer delivery confirmed') }}</dt><dd>{{ agentDateLabel(detail.shipment.deliveredAt) }}</dd></div>
          <div v-if="detail.shipment.notes"><dt>{{ $tx('Notes') }}</dt><dd>{{ detail.shipment.notes }}</dd></div>
        </dl>
        <form v-if="detail.shipment.statusCode !== 'delivered'" class="portal-form agent-operation" @submit.prevent="delivered">
          <label>{{ $tx('Receipt notes (optional)') }}<input v-model="deliveryNote" maxlength="2000" :disabled="busy" /></label>
          <button class="btn-primary" :disabled="busy" data-testid="agent-confirm-client-delivery">{{ $tx('Confirm customer delivery') }}</button>
        </form>
        <h3>{{ $tx('Cards in this return shipment') }}</h3>
        <div class="agent-inventory-cards">
          <article v-for="card in detail.cards" :key="card.id" class="agent-stock-card">
            <div class="agent-toolbar"><strong>{{ card.cardName }}</strong><span class="status-pill">{{ agentStatusLabel(card.statusCode) }}</span></div>
            <code class="agent-inventory-code">{{ card.inventoryCode }}</code>
            <p class="muted-copy">{{ card.languageCode }} · {{ card.intakeNo }}</p>
            <router-link v-if="card.orderNo" :to="{ path: '/nxr/submission-workbench', query: { tab: 'batches', order: card.orderNo, company: api.companyId } }">{{ card.orderNo }}</router-link>
            <div><PrivateOrderPhoto :photo-id="card.frontPhotoId" :label="$tx('Front')" /><PrivateOrderPhoto :photo-id="card.backPhotoId" :label="$tx('Back')" /></div>
          </article>
        </div>
        <AgentTimeline :events="detail.events" :scope="{ shipmentId: detail.shipment.id }" />
      </template>
    </section>
  </div>
</template>
