<script setup lang="ts">
import { tx } from '@/i18n'
import { useAgentApi } from '../lib/agentWorkbench'
import { onMounted, ref, watch } from 'vue'
import { type AgentClient } from '../lib/agentWorkbench'
const api = useAgentApi()
const { fetchAgentClients, fetchAgentClient } = api

const selectedId = defineModel<number>({ required: true })
const props = withDefaults(defineProps<{ disabled?: boolean; activeOnly?: boolean }>(), { activeOnly: true })
const emit = defineEmits<{ selected: [client: AgentClient] }>()
const query = ref(''), clients = ref<AgentClient[]>([]), selected = ref<AgentClient | null>(null), loading = ref(false), error = ref('')
let generation = 0
async function search() {
  const current = ++generation
  loading.value = true; error.value = ''
  try {
    const result = await fetchAgentClients({ query: query.value.trim(), pageSize: 50, active: props.activeOnly ? true : undefined })
    if (current === generation) clients.value = result.items
  } catch (e) { if (current === generation) error.value = e instanceof Error ? e.message : tx('Unable to load customer options.') }
  finally { if (current === generation) loading.value = false }
}
watch(selectedId, async id => {
  selected.value = null
  if (!id) return
  const existing = clients.value.find(client => client.id === id)
  if (existing) { selected.value = existing; emit('selected', existing); return }
  try { const detail = await fetchAgentClient(id); if (selectedId.value === id) { selected.value = detail.client; emit('selected', detail.client) } }
  catch (e) { error.value = e instanceof Error ? e.message : tx('Unable to load the selected customer.') }
}, { immediate: true })
onMounted(search)
</script>
<template><div class="agent-client-picker"><div class="agent-toolbar"><label>{{ $tx('Find customer') }}<input v-model="query" :disabled="disabled" :placeholder="$tx('Enter customer name or reference')" @keydown.enter.prevent="search" /></label><button type="button" class="btn-secondary" :disabled="disabled || loading" @click="search">{{ $tx('Find') }}</button></div><label>{{ $tx('Customer') }}<select v-model="selectedId" :disabled="disabled || loading" required data-testid="agent-client-select"><option :value="0" disabled>{{ $tx('Select a customer') }}</option><option v-if="selected && !clients.some(client => client.id === selectedId)" :value="selected.id">{{ selected.reference }} · {{ selected.displayName }}</option><option v-for="client in clients" :key="client.id" :value="client.id">{{ client.reference }} · {{ client.displayName }}{{ client.active ? '' : $tx(' (archived)') }}</option></select></label><p v-if="error" class="form-error" role="alert">{{ error }}</p></div></template>
