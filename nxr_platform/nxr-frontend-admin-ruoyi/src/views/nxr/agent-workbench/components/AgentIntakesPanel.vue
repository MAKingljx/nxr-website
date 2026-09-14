<script setup lang="ts">
import { tx } from '@/i18n'
import { useAgentApi } from '../lib/agentWorkbench'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'

import { createAgentIntakeLabelPrinter } from '../lib/agentIntakeLabels'
import PrivateOrderPhoto from './AgentPrivatePhoto.vue'
import {
  agentDateLabel, agentStatusLabel, emptyAgentPage, useAgentActions,
  type AgentCard, type AgentIntake, type AgentIntakeDetail, type AgentSubmission,
} from '../lib/agentWorkbench'
import AgentClientPicker from './AgentClientPicker.vue'
import AgentPagination from './AgentPagination.vue'
import AgentSubmissionForm from './AgentSubmissionForm.vue'
import AgentTimeline from './AgentTimeline.vue'

const api = useAgentApi()
const { fetchApplicationConfig } = api
const { fetchAgentIntakes, fetchAgentIntake, createAgentIntake, receiveAgentIntake, checkInAgentCard, uploadAgentCardPhoto } = api

const props = defineProps<{ initialClientId?: number }>()
const rows = ref(emptyAgentPage<AgentIntake>()), detail = ref<AgentIntakeDetail | null>(null)
const loading = ref(false), detailLoading = ref(false), query = ref(''), statusCode = ref(''), clientFilter = ref(props.initialClientId || 0)
const mode = ref<'detail' | 'new' | 'submission'>('detail'), selected = ref<Record<number, AgentIntake>>({}), createdSubmission = ref<AgentSubmission | null>(null)
const form = reactive({ clientId: props.initialClientId || 0, carrierName: '', trackingNumber: '', expectedCardCount: 1, notes: '' })
const cards = ref([{ localId: crypto.randomUUID(), cardName: '', languageCode: 'EN', notes: '' }]), maxCards = ref(200)
const scan = reactive({ inventoryCode: '', conditionNote: '', hasException: false }), receiveNote = ref(''), scanInput = ref<HTMLInputElement | null>(null)
const { busy, error, success, run } = useAgentActions()
const labelPrinter = createAgentIntakeLabelPrinter()
const selectedIntakes = computed(() => Object.values(selected.value))
const editableCards = computed(() => detail.value && ['expected', 'received', 'exception', 'ready'].includes(detail.value.intake.statusCode))
const canScan = computed(() => detail.value && ['received', 'exception', 'ready'].includes(detail.value.intake.statusCode))
let listGeneration = 0, detailGeneration = 0
async function load(page = 1) {
  const current = ++listGeneration
  loading.value = true
  try {
    const result = await fetchAgentIntakes({ page, pageSize: 20, query: query.value.trim(), statusCode: statusCode.value, clientId: clientFilter.value || undefined })
    if (current !== listGeneration) return
    rows.value = result
    result.items.forEach(intake => { if (selected.value[intake.id]) { if (intake.statusCode === 'ready') selected.value[intake.id] = intake; else delete selected.value[intake.id] } })
  } catch (e) { if (current === listGeneration) error.value = e instanceof Error ? e.message : tx('Unable to load intakes.') }
  finally { if (current === listGeneration) loading.value = false }
}
async function show(id: number) {
  if (busy.value) return
  const current = ++detailGeneration
  detailLoading.value = true; mode.value = 'detail'; scan.inventoryCode = ''; scan.conditionNote = ''; scan.hasException = false; error.value = ''; success.value = ''
  try { const result = await fetchAgentIntake(id); if (current === detailGeneration) detail.value = result }
  catch (e) { if (current === detailGeneration) error.value = e instanceof Error ? e.message : tx('Unable to load intake details.') }
  finally { if (current === detailGeneration) detailLoading.value = false }
}
function newIntake() { detailGeneration++; detailLoading.value = false; mode.value = 'new'; createdSubmission.value = null; error.value = ''; success.value = ''; if (clientFilter.value) form.clientId = clientFilter.value }
function addCard() { if (cards.value.length < maxCards.value) cards.value.push({ localId: crypto.randomUUID(), cardName: '', languageCode: 'EN', notes: '' }) }
function fillExpected() { if (Number.isInteger(form.expectedCardCount) && form.expectedCardCount <= maxCards.value) while (cards.value.length < form.expectedCardCount) addCard() }
async function create() {
  if (cards.value.length !== form.expectedCardCount) { error.value = tx('The number of registered cards must match the expected quantity.'); return }
  const payload = { ...form, cards: cards.value.map(({ cardName, languageCode, notes }) => ({ cardName: cardName.trim(), languageCode: languageCode.toUpperCase().trim(), notes })) }
  const saved = await run('intake:create', payload, key => createAgentIntake(payload, key), value => {
    detail.value = value; mode.value = 'detail'
    Object.assign(form, { carrierName: '', trackingNumber: '', expectedCardCount: 1, notes: '' }); cards.value = [{ localId: crypto.randomUUID(), cardName: '', languageCode: 'EN', notes: '' }]
  }, tx('Intake recorded and inventory codes generated.'))
  if (saved) await load()
}
async function receive() {
  const id = detail.value?.intake.id
  if (!id) return
  const payload = { note: receiveNote.value }
  const saved = await run(`intake:${id}:receive`, payload, key => receiveAgentIntake(id, payload, key), value => { detail.value = value; receiveNote.value = '' }, tx('Parcel received. Scan each card to check it into inventory.'))
  if (saved) { await load(rows.value.page); await nextTick(); scanInput.value?.focus() }
}
async function checkIn() {
  const id = detail.value?.intake.id
  if (!id || !canScan.value || !scan.inventoryCode.trim()) return
  if (scan.hasException && !scan.conditionNote.trim()) { error.value = tx('Please describe the exception.'); return }
  const payload = { inventoryCode: scan.inventoryCode.trim(), conditionNote: scan.conditionNote.trim(), hasException: scan.hasException }
  const saved = await run(`intake:${id}:check-in`, payload, key => checkInAgentCard(id, payload, key), value => { detail.value = value; scan.inventoryCode = ''; scan.conditionNote = ''; scan.hasException = false }, payload.hasException ? tx('Exception recorded. Scan again after resolving it to recheck the card.') : tx('Card checked and added to inventory.'))
  if (saved) await load(rows.value.page)
  await nextTick(); scanInput.value?.focus(); if (!saved) scanInput.value?.select()
}
async function upload(card: AgentCard, side: 'front' | 'back', event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]
  if (!file || busy.value) return
  if (!['image/jpeg', 'image/png'].includes(file.type) || file.size > 15 * 1024 * 1024) { error.value = tx('Choose a JPG or PNG photo no larger than 15 MB.'); input.value = ''; return }
  const saved = await run(`card:${card.id}:photo:${side}`, { name: file.name, size: file.size, modified: file.lastModified }, () => uploadAgentCardPhoto(card.id, side, file), () => {}, tx('Photo saved.'))
  input.value = ''
  if (saved && detail.value) { try { detail.value = await fetchAgentIntake(detail.value.intake.id) } catch { error.value = tx('Photo saved, but the preview could not be loaded. Select the intake again.') } }
}
function select(intake: AgentIntake, event: Event) {
  if ((event.target as HTMLInputElement).checked) selected.value[intake.id] = intake
  else delete selected.value[intake.id]
}
async function submitted(value: AgentSubmission) { createdSubmission.value = value; selected.value = {}; mode.value = 'detail'; detail.value = null; await load() }
function printLabels() {
  if (!detail.value || busy.value) return
  try { labelPrinter.open(detail.value.intake, detail.value.cards) }
  catch (e) { error.value = e instanceof Error ? e.message : tx('Unable to open the label preview. Please try again.') }
}
onMounted(async () => { await load(); try { maxCards.value = (await fetchApplicationConfig()).maxCardsPerOrder } catch (e) { error.value = e instanceof Error ? e.message : tx('Unable to load the card quantity limit.') } })
onBeforeUnmount(labelPrinter.close)
</script>

<template>
  <div class="agent-panel" data-testid="agent-intakes-panel">
    <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="success" class="agent-success" role="status">{{ success }}</p>
    <div v-if="createdSubmission" class="agent-success" role="status">{{ $tx('Submission batch created') }} <strong>{{ createdSubmission.batchNo }}</strong>. <router-link :to="{ path: '/nxr/submission-workbench', query: { tab: 'batches', batch: createdSubmission.batchNo, company: api.companyId } }" data-testid="agent-open-batch">{{ $tx('View batch orders') }}</router-link></div>
    <div class="agent-split">
      <aside class="agent-list-pane"><div class="agent-toolbar"><h2>{{ $tx('Intakes and inventory') }}</h2><button type="button" class="btn-primary" :disabled="busy" data-testid="agent-new-intake" @click="newIntake">{{ $tx('Intake registered') }}</button></div>
        <form class="portal-form agent-search" @submit.prevent="load()"><label>{{ $tx('Search intakes') }}<input v-model="query" :placeholder="$tx('Intake or tracking number')" /></label><div class="agent-toolbar"><select v-model="statusCode" :aria-label="$tx('Intake status')" @change="load()"><option value="">{{ $tx('All statuses') }}</option><option value="expected">{{ $tx('Awaiting receipt') }}</option><option value="received">{{ $tx('Awaiting inventory check') }}</option><option value="exception">{{ $tx('Exception') }}</option><option value="ready">{{ $tx('Intake complete') }}</option><option value="submitted">{{ $tx('Submitted for grading') }}</option></select><button class="btn-secondary" :disabled="loading">{{ $tx('Search') }}</button></div><button v-if="clientFilter" type="button" class="text-button" @click="clientFilter = 0; load()">{{ $tx('Clear customer filter') }}</button></form>
        <p v-if="loading" class="muted-copy" role="status">{{ $tx('Loading…') }}</p><p v-else-if="!rows.items.length" class="portal-empty">{{ $tx('No intakes yet.') }}</p>
        <div class="agent-record-list"><div v-for="intake in rows.items" :key="intake.id" class="agent-intake-row"><label v-if="intake.statusCode === 'ready'" class="agent-select-record"><input type="checkbox" :checked="Boolean(selected[intake.id])" :disabled="busy" :aria-label="$tx('Select intake {p1}', { p1: intake.intakeNo })" :data-testid="`agent-select-intake-${intake.id}`" @change="select(intake, $event)" /></label><button type="button" class="agent-record" :class="{ selected: detail?.intake.id === intake.id && mode === 'detail' }" :disabled="busy" @click="show(intake.id)"><strong>{{ intake.intakeNo }}</strong><span>{{ $tx('{p1} · {p2} / {p3} cards', { p1: intake.clientName, p2: intake.checkedInCardCount, p3: intake.expectedCardCount }) }}</span><span>{{ agentStatusLabel(intake.statusCode) }}</span></button></div></div><AgentPagination v-bind="rows" :disabled="loading || busy" @change="load" />
        <div v-if="selectedIntakes.length" class="agent-selection-bar"><span>{{ $tx('{p1} complete intakes selected', { p1: selectedIntakes.length }) }}</span><button type="button" class="btn-primary" :disabled="busy" data-testid="agent-start-submission" @click="mode = 'submission'">{{ $tx('Create submission batch') }}</button><button type="button" class="text-button" :disabled="busy" @click="selected = {}">{{ $tx('Clear selection') }}</button></div>
      </aside>
      <section class="agent-detail-pane">
        <form v-if="mode === 'new'" class="portal-form" data-testid="agent-intake-form" @submit.prevent="create"><div class="agent-toolbar"><h2>{{ $tx('Register customer intake') }}</h2><button type="button" class="text-button" :disabled="busy" @click="mode = 'detail'">{{ $tx('Cancel') }}</button></div><fieldset :disabled="busy"><AgentClientPicker v-model="form.clientId" :disabled="busy" /><div class="form-grid"><label>{{ $tx('Carrier') }}<input v-model="form.carrierName" required maxlength="128" data-testid="agent-intake-carrier" /></label><label>{{ $tx('Tracking number') }}<input v-model="form.trackingNumber" required maxlength="255" data-testid="agent-intake-tracking" /></label><label>{{ $tx('Expected card count (up to {p1})', { p1: maxCards }) }}<input v-model.number="form.expectedCardCount" type="number" min="1" :max="maxCards" required data-testid="agent-intake-quantity" /></label><button type="button" class="btn-secondary agent-align-bottom" @click="fillExpected">{{ $tx('Add remaining card rows') }}</button></div><label>{{ $tx('Intake notes') }}<textarea v-model="form.notes" maxlength="2000" rows="2" /></label><div class="agent-toolbar"><h3>{{ $tx('Individual card registration · {p1} cards', { p1: cards.length }) }}</h3><button type="button" class="btn-secondary" :disabled="cards.length >= maxCards" data-testid="agent-add-card" @click="addCard">{{ $tx('Add card') }}</button></div><div v-for="(card, index) in cards" :key="card.localId" class="agent-card-entry"><div class="agent-toolbar"><strong>{{ $tx('Card {p1}', { p1: index + 1 }) }}</strong><button type="button" class="text-button" :disabled="cards.length <= 1" @click="cards.splice(index, 1)">{{ $tx('Remove') }}</button></div><div class="form-grid"><label>{{ $tx('Card name') }}<input v-model="card.cardName" required maxlength="255" :data-testid="`agent-card-name-${index}`" /></label><label>{{ $tx('Language') }}<input v-model="card.languageCode" required maxlength="32" placeholder="EN / ZH / JA" :data-testid="`agent-card-language-${index}`" /></label></div><label>{{ $tx('Card notes') }}<input v-model="card.notes" maxlength="2000" /></label></div></fieldset><p v-if="cards.length !== form.expectedCardCount" class="muted-copy">{{ $tx('The registered card count must match the expected {p1} cards.', { p1: form.expectedCardCount }) }}</p><button class="btn-primary" :disabled="busy || cards.length !== form.expectedCardCount || !form.clientId" data-testid="agent-create-intake">{{ busy ? $tx('Recording…') : $tx('Register and generate inventory codes') }}</button></form>
        <AgentSubmissionForm v-else-if="mode === 'submission'" :intakes="selectedIntakes" @created="submitted" @close="mode = 'detail'" />
        <p v-else-if="detailLoading" class="portal-empty" role="status">{{ $tx('Loading intake details…') }}</p>
        <template v-else-if="detail"><div class="agent-toolbar"><div><h2>{{ detail.intake.intakeNo }}</h2><p class="muted-copy">{{ detail.intake.clientName }} · {{ agentStatusLabel(detail.intake.statusCode) }}</p></div><span class="status-pill">{{ $tx('{p1} / {p2} cards checked', { p1: detail.intake.checkedInCardCount, p2: detail.intake.expectedCardCount }) }}</span></div><dl class="agent-facts"><div><dt>{{ $tx('Customer intakes') }}</dt><dd>{{ detail.intake.carrierName }} · {{ detail.intake.trackingNumber }}</dd></div><div><dt>{{ $tx('Delivered at') }}</dt><dd>{{ agentDateLabel(detail.intake.receivedAt) }}</dd></div><div v-if="detail.intake.notes"><dt>{{ $tx('Notes') }}</dt><dd>{{ detail.intake.notes }}</dd></div><div v-if="detail.intake.orderNo"><dt>{{ $tx('Submission order') }}</dt><dd><router-link :to="{ path: '/nxr/submission-workbench', query: { tab: 'batches', order: detail.intake.orderNo, company: api.companyId } }">{{ detail.intake.orderNo }}</router-link> · <router-link :to="{ path: '/nxr/submission-workbench', query: { tab: 'batches', company: api.companyId } }">{{ detail.intake.batchNo }}</router-link></dd></div></dl>
          <form v-if="detail.intake.statusCode === 'expected'" class="portal-form agent-operation" @submit.prevent="receive"><label>{{ $tx('Receipt notes (optional)') }}<input v-model="receiveNote" maxlength="2000" :disabled="busy" /></label><button class="btn-primary" :disabled="busy" data-testid="agent-receive-intake">{{ $tx('Confirm intake receipt') }}</button></form>
          <form v-if="canScan" class="portal-form agent-operation" @submit.prevent="checkIn"><h3>{{ $tx('Check cards individually') }}</h3><label>{{ $tx('Scan or enter inventory code') }}<input ref="scanInput" v-model="scan.inventoryCode" required autocomplete="off" autocapitalize="off" spellcheck="false" :disabled="busy" :placeholder="$tx('Scan, then press Enter')" data-testid="agent-check-in-code" /></label><label class="agent-checkbox"><input v-model="scan.hasException" type="checkbox" :disabled="busy" data-testid="agent-check-in-exception" />{{ $tx('This card has an exception') }}</label><label>{{ scan.hasException ? $tx('Exception details') : $tx('Inventory notes (optional)') }}<input v-model="scan.conditionNote" maxlength="2000" :required="scan.hasException" :disabled="busy" data-testid="agent-check-in-note" /></label><button class="btn-primary" :disabled="busy" data-testid="agent-check-in-submit">{{ busy ? $tx('Checking…') : scan.hasException ? $tx('Record exception') : $tx('Check into inventory') }}</button><p v-if="detail.intake.exceptionCardCount" class="muted-copy">{{ $tx('{p1} cards have exceptions. Resolve them, clear the exception checkbox and scan again to recheck.', { p1: detail.intake.exceptionCardCount }) }}</p></form>
          <p v-if="detail.intake.statusCode === 'ready'" class="agent-success">{{ $tx('All cards are in inventory. Select complete intakes on the left to create a submission batch.') }}</p>
          <div class="agent-toolbar"><h3>{{ $tx('Card inventory ({p1})', { p1: detail.cards.length }) }}</h3><button type="button" class="btn-secondary" :disabled="busy || !detail.cards.length" data-testid="agent-print-intake-labels" @click="printLabels">{{ $tx('Print intake labels') }}</button></div><div class="agent-inventory-cards"><article v-for="card in detail.cards" :key="card.id" class="agent-stock-card" :data-testid="`agent-stock-card-${card.id}`"><div class="agent-toolbar"><strong>{{ card.cardName }}</strong><span class="status-pill">{{ card.statusCode === 'expected' ? $tx('Awaiting inventory check') : agentStatusLabel(card.statusCode) }}</span></div><code class="agent-inventory-code" :data-testid="`agent-inventory-code-${card.id}`">{{ card.inventoryCode }}</code><p class="muted-copy">{{ card.languageCode }}<span v-if="card.notes"> · {{ card.notes }}</span></p><p v-if="card.conditionNote" :class="card.statusCode === 'exception' ? 'form-error' : 'muted-copy'">{{ card.conditionNote }}</p><div><PrivateOrderPhoto :photo-id="card.frontPhotoId" :label="$tx('Front')" /><PrivateOrderPhoto :photo-id="card.backPhotoId" :label="$tx('Back')" /></div><div v-if="editableCards" class="agent-photo-controls"><label>{{ $tx('Front photo') }}<input type="file" accept="image/jpeg,image/png" :disabled="busy" :data-testid="`agent-photo-front-${card.id}`" @change="upload(card, 'front', $event)" /></label><label>{{ $tx('Back photo') }}<input type="file" accept="image/jpeg,image/png" :disabled="busy" @change="upload(card, 'back', $event)" /></label></div></article></div><AgentTimeline :events="detail.events" :scope="{ intakeId: detail.intake.id }" />
        </template>
        <p v-else class="portal-empty">{{ $tx('Select an intake to confirm receipt, count cards and check inventory.') }}</p>
      </section>
    </div>
  </div>
</template>
