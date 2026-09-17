<script setup lang="ts">
import { tx } from '@/i18n'
import { useAgentApi } from '../lib/agentWorkbench'
import { computed, onMounted, ref, watch } from 'vue'
import { agentDateLabel, agentEventLabel, emptyAgentPage, type AgentEvent } from '../lib/agentWorkbench'
import AgentPagination from './AgentPagination.vue'
const api = useAgentApi()
const { fetchAgentEvents } = api

const props = defineProps<{ events: AgentEvent[]; scope: { clientId?: number; intakeId?: number; shipmentId?: number } }>()
const history = ref(emptyAgentPage<AgentEvent>()), loaded = ref(false), loading = ref(false), error = ref(''), open = ref(true)
const events = computed(() => loaded.value ? history.value.items : props.events)
const shipmentSteps = [
  { code: 'shipment_created', label: 'Shipment created' },
  { code: 'card_return_shipped', label: 'Shipped' },
  { code: 'shipment_in_transit', label: 'In transit' },
  { code: 'shipment_out_for_delivery', label: 'Out for delivery' },
  { code: 'client_received_card', label: 'Delivered' },
]
const shipmentProgress = computed(() => {
  if (!props.scope.shipmentId) return []
  const codes = new Set(events.value.map(event => event.eventCode))
  if (codes.has('shipment_delivered') || codes.has('return_shipment_delivered')) codes.add('client_received_card')
  let current = -1
  shipmentSteps.forEach((step, index) => { if (codes.has(step.code)) current = index })
  return shipmentSteps.map((step, index) => ({ ...step, reached: index <= current, current: index === current }))
})
let generation = 0
async function load(page = 1) {
  if (loading.value) return
  const current = ++generation
  loading.value = true; error.value = ''
  try { const result = await fetchAgentEvents({ ...props.scope, page, pageSize: 20 }); if (current === generation) { history.value = result; loaded.value = true } }
  catch (e) { if (current === generation) error.value = e instanceof Error ? e.message : tx('Unable to load history.') }
  finally { if (current === generation) loading.value = false }
}
function toggle(event: Event) { open.value = (event.target as HTMLDetailsElement).open; if (open.value && !loaded.value) load() }
watch(() => [JSON.stringify(props.scope), props.events[0]?.id], () => { generation++; loaded.value = false; if (open.value) load() })
onMounted(() => load())
</script>
<template>
  <details open class="agent-history" @toggle="toggle"><summary>{{ loaded ? $tx('Status history ({count})', { count: history.total }) : $tx('Status history') }}</summary>
    <ol v-if="shipmentProgress.length" class="agent-shipment-progress" :aria-label="$tx('Shipment progress')"><li v-for="step in shipmentProgress" :key="step.code" :class="{ reached: step.reached, current: step.current }"><span class="progress-dot">{{ step.reached ? '✓' : '' }}</span><strong>{{ $tx(step.label) }}</strong></li></ol>
    <p v-if="error" class="form-error" role="alert">{{ error }} <button type="button" class="text-button" @click="load()">{{ $tx('Retry') }}</button></p><p v-if="loading" class="muted-copy" role="status">{{ $tx('Loading history…') }}</p>
    <p v-if="!events.length" class="muted-copy">{{ $tx('No records yet.') }}</p>
    <ol v-else class="agent-timeline"><li v-for="event in events" :key="event.id"><div><strong>{{ agentEventLabel(event.eventCode) }}</strong><time>{{ agentDateLabel(event.createdAt) }}</time></div><code v-if="event.inventoryCode">{{ event.inventoryCode }}</code><p v-if="event.note">{{ event.note }}</p></li></ol>
    <AgentPagination v-if="loaded" v-bind="history" :disabled="loading" @change="load" />
  </details>
</template>

<style scoped>
.agent-shipment-progress {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 0;
  list-style: none;
  margin: 18px 0 24px;
  padding: 0;
}
.agent-shipment-progress li {
  position: relative;
  display: grid;
  justify-items: center;
  gap: 8px;
  min-width: 0;
  color: var(--text2, #8b93a7);
  text-align: center;
  font-size: 12px;
}
.agent-shipment-progress li::before {
  position: absolute;
  top: 11px;
  right: 50%;
  width: 100%;
  height: 2px;
  background: var(--border2, #dfe3eb);
  content: '';
}
.agent-shipment-progress li:first-child::before { display: none; }
.agent-shipment-progress li.reached::before { background: var(--el-color-primary, #409eff); }
.agent-shipment-progress li.current { color: var(--el-color-primary, #409eff); }
.progress-dot {
  position: relative;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: 2px solid var(--border2, #dfe3eb);
  border-radius: 50%;
  background: var(--el-bg-color, #fff);
  font-size: 12px;
  font-weight: 700;
}
.reached .progress-dot { border-color: var(--el-color-primary, #409eff); background: var(--el-color-primary, #409eff); color: #fff; }
.current .progress-dot { box-shadow: 0 0 0 4px color-mix(in srgb, var(--el-color-primary, #409eff) 18%, transparent); }
@media (max-width: 640px) {
  .agent-shipment-progress { grid-template-columns: 1fr; gap: 10px; justify-items: stretch; }
  .agent-shipment-progress li { grid-template-columns: 24px minmax(0, 1fr); justify-items: start; align-items: center; text-align: left; }
  .agent-shipment-progress li::before { top: -10px; left: 11px; right: auto; width: 2px; height: 10px; }
  .agent-shipment-progress li:first-child::before { display: none; }
}
</style>
