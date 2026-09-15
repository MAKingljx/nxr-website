<script setup lang="ts">
import { tx } from '@/i18n'
import { useAgentApi } from '../lib/agentWorkbench'
import { nextTick, onMounted, ref } from 'vue'
import {
  agentAddressLabel, agentDateLabel, agentStatusLabel, emptyAgentClient, emptyAgentPage,
  useAgentActions,
  type AgentClient, type AgentClientDetail, type AgentClientInput,
} from '../lib/agentWorkbench'
import AgentAddressFields from './AgentAddressFields.vue'
import AgentPagination from './AgentPagination.vue'
import AgentTimeline from './AgentTimeline.vue'

const props = defineProps<{ initialRecordId?: number }>()
const api = useAgentApi()
const { fetchAgentClients, fetchAgentClient, createAgentClient, updateAgentClient } = api

const emit = defineEmits<{ navigate: [tab: 'intakes' | 'returns', clientId: number] }>()
const clients = ref(emptyAgentPage<AgentClient>()), detail = ref<AgentClientDetail | null>(null)
const query = ref(''), active = ref('true'), loading = ref(false), detailLoading = ref(false)
const editing = ref(false), editId = ref<number | null>(null), form = ref<AgentClientInput>(emptyAgentClient())
const detailSection = ref<HTMLElement | null>(null), selectedClientId = ref<number | null>(null)
const { busy, error, success, run } = useAgentActions()
let listGeneration = 0, detailGeneration = 0
let detailTrigger: HTMLElement | null = null
function rememberTrigger(event?: Event) {
  const target = event?.currentTarget
  if (target instanceof HTMLElement && !detailSection.value?.contains(target)) detailTrigger = target
}
async function revealDetail(generation: number) {
  await nextTick()
  if (generation !== detailGeneration || !detailSection.value) return
  detailSection.value.focus({ preventScroll: true })
  detailSection.value.scrollIntoView({ block: 'start', behavior: 'auto' })
}
function closeDetail() {
  if (busy.value) return
  detailGeneration++
  detailLoading.value = false; editing.value = false; detail.value = null; selectedClientId.value = null
  detailTrigger?.focus()
}
async function load(page = 1) {
  const generation = ++listGeneration
  loading.value = true
  try {
    const result = await fetchAgentClients({ page, pageSize: 20, query: query.value.trim(), active: active.value || undefined })
    if (generation === listGeneration) clients.value = result
  } catch (e) { if (generation === listGeneration) error.value = e instanceof Error ? e.message : tx('Unable to load customers.') }
  finally { if (generation === listGeneration) loading.value = false }
}
async function selectClient(id: number, event?: Event) {
  if (busy.value) return
  rememberTrigger(event)
  const generation = ++detailGeneration
  selectedClientId.value = id
  detail.value = null; detailLoading.value = true; editing.value = false; error.value = ''; success.value = ''
  void revealDetail(generation)
  try { const result = await fetchAgentClient(id); if (generation === detailGeneration) detail.value = result }
  catch (e) {
    if (generation === detailGeneration) {
      selectedClientId.value = null
      error.value = e instanceof Error ? e.message : tx('Unable to load customer details.')
      detailTrigger?.focus()
    }
  }
  finally { if (generation === detailGeneration) detailLoading.value = false }
}
function clientInput(client?: AgentClient) {
  const blank = emptyAgentClient()
  if (client) Object.keys(blank).forEach(key => { const typedKey = key as keyof AgentClientInput; Object.assign(blank, { [typedKey]: client[typedKey] ?? blank[typedKey] }) })
  return blank
}
function edit(client?: AgentClient, event?: Event) {
  if (busy.value) return
  rememberTrigger(event)
  detailGeneration++; detailLoading.value = false
  editId.value = client?.id ?? null
  selectedClientId.value = editId.value
  form.value = clientInput(client); editing.value = true; error.value = ''; success.value = ''
  void revealDetail(detailGeneration)
}
async function save() {
  const payload = { ...form.value }
  const id = editId.value
  const generation = ++detailGeneration
  let savedId = 0
  const saved = await run(id ? `client:${id}` : 'client:create', payload, key => id ? updateAgentClient(id, payload) : createAgentClient(payload, key), value => {
    savedId = value.id
    selectedClientId.value = value.id
    editing.value = false
    detail.value = { client: value, intakes: detail.value?.client.id === value.id ? detail.value.intakes : [], shipments: detail.value?.client.id === value.id ? detail.value.shipments : [], events: detail.value?.client.id === value.id ? detail.value.events : [] }
  }, tx('Customer record saved.'))
  if (saved) {
    await load()
    try { const value = await fetchAgentClient(savedId); if (generation === detailGeneration) detail.value = value }
    catch { if (generation === detailGeneration) error.value = tx('Record saved, but history could not be loaded. Select the customer again.') }
  }
}
async function toggleActive() {
  const client = detail.value?.client
  if (!client) return
  const payload = { ...clientInput(client), active: !client.active }
  const saved = await run(`client:${client.id}:active`, payload, () => updateAgentClient(client.id, payload), value => { if (detail.value) detail.value.client = value }, payload.active ? tx('Customer restored.') : tx('Customer archived. Historical records have been retained.'))
  if (saved) await load(clients.value.page)
}
onMounted(async () => {
  const generation = detailGeneration
  await load()
  if (props.initialRecordId && generation === detailGeneration) await selectClient(props.initialRecordId)
})
</script>

<template>
  <div class="agent-panel" data-testid="agent-clients-panel">
    <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="success" class="agent-success" role="status">{{ success }}</p>
    <div class="agent-list-layout">
      <form class="portal-form agent-list-filters" @submit.prevent="load()">
        <label class="agent-filter-field agent-filter-grow">{{ $tx('Search customers') }}<input v-model="query" :placeholder="$tx('Reference, name or contact details')" data-testid="agent-client-search" /></label>
        <label class="agent-filter-field">{{ $tx('Customer status') }}<select v-model="active" @change="load()"><option value="true">{{ $tx('Active') }}</option><option value="false">{{ $tx('Archived') }}</option><option value="">{{ $tx('All customers') }}</option></select></label>
        <div class="agent-list-actions"><button type="submit" class="btn-secondary" :disabled="loading">{{ $tx('Search') }}</button><button type="button" class="btn-primary" :disabled="busy" data-testid="agent-new-client" @click="edit(undefined, $event)">{{ $tx('Add customer') }}</button></div>
      </form>
      <div class="agent-table-scroll" :aria-busy="loading">
        <table class="portal-table agent-results-table" :aria-label="$tx('Customer records')" data-testid="agent-client-table">
          <thead><tr><th scope="col">{{ $tx('Customer name') }}</th><th scope="col">{{ $tx('Customer reference') }}</th><th scope="col">{{ $tx('Contact details') }}</th><th scope="col">{{ $tx('Status') }}</th><th scope="col">{{ $tx('Updated At') }}</th><th scope="col">{{ $tx('Actions') }}</th></tr></thead>
          <tbody>
            <tr v-if="loading"><td colspan="6" class="portal-empty" role="status">{{ $tx('Loading…') }}</td></tr>
            <tr v-else-if="!clients.items.length"><td colspan="6" class="portal-empty">{{ $tx('No customers yet.') }}</td></tr>
            <tr v-for="client in loading ? [] : clients.items" :key="client.id" :class="{ 'agent-row-selected': selectedClientId === client.id }" :data-client-id="client.id">
              <th scope="row">{{ client.displayName }}</th><td>{{ client.reference }}</td>
              <td>{{ client.phone || client.email || '—' }}<small v-if="client.phone && client.email">{{ client.email }}</small></td>
              <td>{{ client.active ? $tx('Active') : $tx('Archived') }}</td><td>{{ agentDateLabel(client.updatedAt) }}</td>
              <td><div class="agent-list-actions"><button type="button" class="text-button" :disabled="busy" :aria-label="$tx('View') + ': ' + client.displayName" data-testid="agent-view-client" @click="selectClient(client.id, $event)">{{ $tx('View') }}</button><button type="button" class="text-button" :disabled="busy" :aria-label="$tx('Edit') + ': ' + client.displayName" data-testid="agent-edit-client" @click="edit(client, $event)">{{ $tx('Edit') }}</button></div></td>
            </tr>
          </tbody>
        </table>
      </div>
      <AgentPagination v-bind="clients" :disabled="loading || busy" @change="load" />
      <section v-if="editing || detailLoading || detail" ref="detailSection" class="agent-detail-pane agent-detail-section" tabindex="-1" :aria-label="editing ? (editId ? $tx('Edit customer') : $tx('Add customer')) : $tx('Customer records')" data-testid="agent-client-detail">
        <div v-if="!editing" class="agent-list-actions"><button type="button" class="text-button" :disabled="busy" data-testid="agent-close-client" @click="closeDetail">{{ $tx('Close details') }}</button></div>
        <form v-if="editing" class="portal-form" data-testid="agent-client-form" @submit.prevent="save"><div class="agent-toolbar"><h2>{{ editId ? $tx('Edit customer') : $tx('Add customer') }}</h2><button type="button" class="text-button" :disabled="busy" @click="closeDetail">{{ $tx('Cancel') }}</button></div><fieldset :disabled="busy"><div class="form-grid"><label>{{ $tx('Customer reference') }}<input v-model="form.reference" required maxlength="64" data-testid="agent-client-reference" /></label><label>{{ $tx('Customer name') }}<input v-model="form.displayName" required maxlength="128" data-testid="agent-client-name" /></label><label class="form-wide">{{ $tx('Email (optional)') }}<input v-model="form.email" type="email" maxlength="191" /></label></div><AgentAddressFields v-model="form" :required="false" /><p class="muted-copy">{{ $tx('The delivery address can be completed before returning cards to the customer.') }}</p><label>{{ $tx('Notes') }}<textarea v-model="form.notes" maxlength="2000" rows="3" /></label></fieldset><button class="btn-primary" :disabled="busy" data-testid="agent-save-client">{{ busy ? $tx('Saving…') : $tx('Save customer') }}</button></form>
        <p v-else-if="detailLoading" class="portal-empty" role="status">{{ $tx('Loading customer details…') }}</p>
        <template v-else-if="detail"><div class="agent-toolbar"><div><h2>{{ detail.client.displayName }}</h2><p class="muted-copy">{{ detail.client.reference }} · {{ detail.client.active ? $tx('Active') : $tx('Archived') }}</p></div><button type="button" class="btn-secondary" :disabled="busy" @click="edit(detail.client)">{{ $tx('Edit') }}</button></div><dl class="agent-facts"><div><dt>{{ $tx('Contact details') }}</dt><dd>{{ [detail.client.phone, detail.client.email].filter(Boolean).join(' · ') || $tx('Not provided') }}</dd></div><div><dt>{{ $tx('Delivery address') }}</dt><dd>{{ [detail.client.contactName, agentAddressLabel(detail.client)].filter(Boolean).join(' · ') || $tx('To be completed') }}</dd></div><div v-if="detail.client.notes"><dt>{{ $tx('Notes') }}</dt><dd>{{ detail.client.notes }}</dd></div></dl><div class="agent-actions"><button type="button" class="btn-primary" @click="emit('navigate', 'intakes', detail.client.id)">{{ $tx('Customer intakes') }}</button><button type="button" class="btn-secondary" @click="emit('navigate', 'returns', detail.client.id)">{{ $tx('View returns') }}</button><button type="button" class="text-button" :disabled="busy" @click="toggleActive">{{ detail.client.active ? $tx('Archive customer') : $tx('Restore customer') }}</button></div>
          <h3>{{ $tx('Recent intakes ({p1})', { p1: detail.intakes.length }) }}</h3><p v-if="!detail.intakes.length" class="muted-copy">{{ $tx('No intakes yet.') }}</p><div v-else class="table-scroll"><table class="portal-table"><thead><tr><th>{{ $tx('Intake') }}</th><th>{{ $tx('Cards') }}</th><th>{{ $tx('Status') }}</th><th>{{ $tx('Submission order') }}</th></tr></thead><tbody><tr v-for="intake in detail.intakes" :key="intake.id"><td>{{ intake.intakeNo }}<small>{{ agentDateLabel(intake.createdAt) }}</small></td><td>{{ intake.checkedInCardCount }} / {{ intake.expectedCardCount }}</td><td>{{ agentStatusLabel(intake.statusCode) }}</td><td><router-link v-if="intake.orderNo" :to="{ path: '/nxr/submission-workbench', query: { tab: 'batches', order: intake.orderNo, company: api.companyId } }">{{ intake.orderNo }}</router-link><span v-else>—</span></td></tr></tbody></table></div>
          <h3>{{ $tx('Recent returns ({p1})', { p1: detail.shipments.length }) }}</h3><p v-if="!detail.shipments.length" class="muted-copy">{{ $tx('No return shipments yet.') }}</p><div v-else class="table-scroll"><table class="portal-table"><thead><tr><th>{{ $tx('Return shipment') }}</th><th>{{ $tx('Shipping') }}</th><th>{{ $tx('Cards') }}</th><th>{{ $tx('Status') }}</th></tr></thead><tbody><tr v-for="shipment in detail.shipments" :key="shipment.id"><td>{{ shipment.shipmentNo }}</td><td>{{ shipment.carrierName }}<small>{{ shipment.trackingNumber }}</small></td><td>{{ shipment.cardCount }}</td><td>{{ agentStatusLabel(shipment.statusCode) }}</td></tr></tbody></table></div><AgentTimeline :events="detail.events" :scope="{ clientId: detail.client.id }" />
        </template>
      </section>
    </div>
  </div>
</template>
