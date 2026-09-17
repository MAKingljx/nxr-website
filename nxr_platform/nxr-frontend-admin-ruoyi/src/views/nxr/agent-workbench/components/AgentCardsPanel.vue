<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { tx } from '@/i18n'
import { agentDateLabel, agentStatusLabel, emptyAgentPage, useAgentApi, type AgentCard, type AgentEvent } from '../lib/agentWorkbench'
import AgentPagination from './AgentPagination.vue'
import AgentPrivatePhoto from './AgentPrivatePhoto.vue'
import nxrLogo from '@/assets/logo/nxr-logo-circle.png'

const api = useAgentApi()
const rows = ref(emptyAgentPage<AgentCard>())
const query = ref('')
const statusCode = ref('')
const loading = ref(false)
const error = ref('')
const selected = ref<AgentCard | null>(null)
const events = ref<AgentEvent[]>([])
const eventLoading = ref(false)
const drawerOpen = computed({
  get: () => Boolean(selected.value),
  set: value => { if (!value) { selected.value = null; events.value = [] } },
})

async function load(page = 1) {
  loading.value = true
  error.value = ''
  try {
    rows.value = await api.fetchAgentCards({ page, pageSize: 20, query: query.value.trim(), statusCode: statusCode.value || undefined })
  } catch (e) {
    error.value = e instanceof Error ? e.message : tx('Unable to load card data.')
  } finally { loading.value = false }
}

async function show(card: AgentCard) {
  selected.value = card
  events.value = []
  eventLoading.value = true
  try {
    const result = await api.fetchAgentEvents({ clientId: card.clientId, intakeId: card.intakeId, page: 1, pageSize: 100 })
    events.value = result.items.filter(event => event.inventoryCode === card.inventoryCode)
  } catch { events.value = [] }
  finally { eventLoading.value = false }
}

onMounted(() => load())
</script>

<template>
  <section class="agent-panel agent-list-layout" data-testid="agent-cards-panel">
    <div class="agent-card-brand" aria-label="NXR logo">
      <img :src="nxrLogo" alt="NXR logo" />
      <strong>NXR</strong>
    </div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <form class="portal-form agent-list-filters" @submit.prevent="load()">
      <label class="agent-filter-field agent-filter-grow">{{ $tx('Search card data') }}<input v-model="query" :placeholder="$tx('Card code, official number, customer or intake')" /></label>
      <label class="agent-filter-field">{{ $tx('Card status') }}<select v-model="statusCode"><option value="">{{ $tx('All statuses') }}</option><option value="expected">{{ $tx('Awaiting receipt') }}</option><option value="in_stock">{{ $tx('In inventory') }}</option><option value="submitted">{{ $tx('Submitted for grading') }}</option><option value="returned">{{ $tx('Returned card checked') }}</option><option value="return_shipped">{{ $tx('Shipped to customer') }}</option><option value="delivered">{{ $tx('Delivered') }}</option></select></label>
      <div class="agent-list-actions"><button class="btn-secondary" type="submit" :disabled="loading">{{ $tx('Search') }}</button></div>
    </form>
    <div class="agent-table-scroll" :aria-busy="loading">
      <table class="portal-table agent-results-table" data-testid="agent-card-data-table">
        <thead><tr><th>{{ $tx('Card code') }}</th><th>{{ $tx('Official number') }}</th><th>{{ $tx('Card') }}</th><th>{{ $tx('Customer') }}</th><th>{{ $tx('Intake / Batch') }}</th><th>{{ $tx('Order / Certificate') }}</th><th>{{ $tx('Status') }}</th><th>{{ $tx('Updated At') }}</th><th>{{ $tx('Actions') }}</th></tr></thead>
        <tbody>
          <tr v-if="loading"><td colspan="9" class="portal-empty">{{ $tx('Loading…') }}</td></tr>
          <tr v-else-if="!rows.items.length"><td colspan="9" class="portal-empty">{{ $tx('No card data yet.') }}</td></tr>
          <tr v-for="card in rows.items" v-else :key="card.id">
            <td><code>{{ card.inventoryCode }}</code></td>
            <td>{{ card.officialCardNumber || '—' }}</td>
            <td><strong>{{ card.cardName }}</strong><small>{{ card.languageCode }}</small></td>
            <td>{{ card.clientName }}</td>
            <td>{{ card.intakeNo }}<small>{{ card.batchNo || '—' }}</small></td>
            <td>{{ card.orderNo || '—' }}<small>{{ card.gradingCertId || '—' }}</small></td>
            <td><span class="status-pill">{{ agentStatusLabel(card.statusCode) }}</span></td>
            <td>{{ agentDateLabel(card.checkedInAt || card.returnedAt) }}</td>
            <td><button type="button" class="text-button" @click="show(card)">{{ $tx('View') }}</button></td>
          </tr>
        </tbody>
      </table>
    </div>
    <AgentPagination v-bind="rows" :disabled="loading" @change="load" />

    <el-drawer v-model="drawerOpen" :title="selected?.cardName || $tx('Card details')" size="min(860px, 96vw)" direction="rtl" destroy-on-close data-testid="agent-card-detail-drawer">
      <template v-if="selected">
        <dl class="agent-facts">
          <div><dt>{{ $tx('Card code') }}</dt><dd><code>{{ selected.inventoryCode }}</code></dd></div>
          <div><dt>{{ $tx('Official number') }}</dt><dd>{{ selected.officialCardNumber || '—' }}</dd></div>
          <div><dt>{{ $tx('Card') }}</dt><dd>{{ selected.cardName }} · {{ selected.languageCode }}</dd></div>
          <div><dt>{{ $tx('Customer') }}</dt><dd>{{ selected.clientName }}</dd></div>
          <div><dt>{{ $tx('Intake') }}</dt><dd>{{ selected.intakeNo }}</dd></div>
          <div><dt>{{ $tx('Submission batch') }}</dt><dd>{{ selected.batchNo || '—' }}</dd></div>
          <div><dt>{{ $tx('Order') }}</dt><dd>{{ selected.orderNo || '—' }}</dd></div>
          <div><dt>{{ $tx('Certificate ID') }}</dt><dd>{{ selected.gradingCertId || '—' }}</dd></div>
          <div><dt>{{ $tx('Status') }}</dt><dd><span class="status-pill">{{ agentStatusLabel(selected.statusCode) }}</span></dd></div>
          <div><dt>{{ $tx('Condition note') }}</dt><dd>{{ selected.conditionNote || selected.notes || '—' }}</dd></div>
        </dl>
        <div class="agent-inventory-cards"><article class="agent-stock-card"><strong>{{ $tx('Front photo') }}</strong><AgentPrivatePhoto :photo-id="selected.frontPhotoId" :label="$tx('Front')" /></article><article class="agent-stock-card"><strong>{{ $tx('Back photo') }}</strong><AgentPrivatePhoto :photo-id="selected.backPhotoId" :label="$tx('Back')" /></article></div>
        <h3>{{ $tx('Card status history') }}</h3>
        <p v-if="eventLoading" class="muted-copy">{{ $tx('Loading history…') }}</p>
        <ol v-else-if="events.length" class="agent-timeline"><li v-for="event in events" :key="event.id"><div><strong>{{ event.eventCode }}</strong><time>{{ agentDateLabel(event.createdAt) }}</time></div><p v-if="event.note">{{ event.note }}</p></li></ol>
        <p v-else class="muted-copy">{{ $tx('No card history yet.') }}</p>
      </template>
    </el-drawer>
  </section>
</template>

<style scoped>
.agent-card-brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  width: fit-content;
  margin: -4px 0 -8px;
  color: var(--el-text-color-primary);
  font-size: 14px;
  letter-spacing: .08em;
}

.agent-card-brand img {
  width: 30px;
  height: 30px;
  object-fit: contain;
}
</style>
