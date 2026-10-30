<script setup lang="ts">
import { tx } from '@/i18n'
import { useAgentApi } from '../lib/agentWorkbench'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import PrivateOrderPhoto from './AgentPrivatePhoto.vue'
import {
  agentAddressLabel, agentDateLabel, agentStatusLabel,
  emptyAgentAddress, emptyAgentPage, useAgentActions,
  type AgentCard, type AgentClient, type AgentShipment, type AgentShipmentDetail,
} from '../lib/agentWorkbench'
import AgentAddressFields from './AgentAddressFields.vue'
import AgentClientPicker from './AgentClientPicker.vue'
import AgentPagination from './AgentPagination.vue'
import AgentTimeline from './AgentTimeline.vue'

const api = useAgentApi()
const { fetchAgentCards, fetchAgentShipments, fetchAgentShipment, createAgentShipment, deliverAgentShipment } = api

const props = defineProps<{ initialClientId?: number; initialRecordId?: number }>()
const shipments = ref(emptyAgentPage<AgentShipment>()), returnCards = ref(emptyAgentPage<AgentCard>())
const detail = ref<AgentShipmentDetail | null>(null)
const mode = ref<'list' | 'new' | 'detail'>('list'), query = ref(''), clientFilter = ref(props.initialClientId || 0)
const detailSection = ref<HTMLElement | null>(null), searchInput = ref<HTMLInputElement | null>(null)
const shipmentLoading = ref(false), detailLoading = ref(false), returnLoading = ref(false)
const deliveryNote = ref('')
const form = reactive({ clientId: props.initialClientId || 0, carrierName: '', trackingNumber: '', note: '' })
const address = ref(emptyAgentAddress()), selectedClient = ref<AgentClient | null>(null), selected = ref<Record<number, AgentCard>>({})
const selectedCards = computed(() => Object.values(selected.value))
const { busy, error, success, run } = useAgentActions()
const operationOpen = computed({
  get: () => mode.value !== 'list',
  set: value => { if (!value) void closeOperation() },
})
let shipmentGeneration = 0, detailGeneration = 0, returnGeneration = 0
async function loadShipments(page = 1) {
  const current = ++shipmentGeneration
  shipmentLoading.value = true
  try { const result = await fetchAgentShipments({ page, pageSize: 20, query: query.value.trim(), clientId: clientFilter.value || undefined }); if (current === shipmentGeneration) shipments.value = result }
  catch (e) { if (current === shipmentGeneration) error.value = e instanceof Error ? e.message : tx('Unable to load return shipment history.') }
  finally { if (current === shipmentGeneration) shipmentLoading.value = false }
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
function selectCard(card: AgentCard, event: Event) {
  if ((event.target as HTMLInputElement).checked) selected.value[card.id] = card
  else delete selected.value[card.id]
}
async function createShipment() {
  if (!selectedCards.value.length || !form.clientId || !selectedClient.value) return
  if (selectedCards.value.some(card => card.clientId !== form.clientId || card.statusCode !== 'returned')) { error.value = tx('Select available cards belonging to the same customer.'); return }
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
function clearFilter() { if (busy.value) return; clientFilter.value = 0; loadShipments() }
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

    <el-drawer v-model="operationOpen" :title="mode === 'new' ? $tx('Create customer return') : detail?.shipment.shipmentNo || $tx('Return shipment')" size="min(1100px, 96vw)" direction="rtl" destroy-on-close :close-on-click-modal="!busy" :show-close="!busy" data-testid="agent-return-operation-drawer">
    <section v-if="mode !== 'list'" ref="detailSection" class="agent-detail-section" tabindex="-1" aria-labelledby="agent-return-operation-heading" data-testid="agent-return-operation">
      <div class="agent-toolbar">
        <h2 id="agent-return-operation-heading">{{ mode === 'new' ? $tx('Create customer return') : detail?.shipment.shipmentNo || $tx('Return shipment') }}</h2>
        <button type="button" class="text-button" :disabled="busy" data-testid="agent-return-close" @click="closeOperation">{{ mode === 'new' ? $tx('Cancel') : $tx('Close') }}</button>
      </div>

      <form v-if="mode === 'new'" class="portal-form" data-testid="agent-return-form" @submit.prevent="createShipment">
        <fieldset :disabled="busy">
          <AgentClientPicker v-model="form.clientId" :active-only="false" :disabled="busy" @selected="chooseClient" />
          <h3>{{ $tx('Select cards to return · {p1} selected', { p1: selectedCards.length }) }}</h3>
          <p v-if="returnLoading" class="muted-copy" role="status">{{ $tx('Loading cards available for return…') }}</p>
          <p v-else-if="!returnCards.items.length" class="muted-copy">{{ $tx('No cards are available to return to this customer.') }}</p>
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
    </el-drawer>
  </div>
</template>
