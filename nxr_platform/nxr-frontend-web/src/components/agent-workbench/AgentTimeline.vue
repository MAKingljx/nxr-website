<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { agentDateLabel, agentEventLabel, emptyAgentPage, fetchAgentEvents, type AgentEvent } from '../../lib/agentWorkbench'
import AgentPagination from './AgentPagination.vue'
const props = defineProps<{ events: AgentEvent[]; scope: { clientId?: number; intakeId?: number; shipmentId?: number } }>()
const history = ref(emptyAgentPage<AgentEvent>()), loaded = ref(false), loading = ref(false), error = ref(''), open = ref(false)
const events = computed(() => loaded.value ? history.value.items : props.events)
let generation = 0
async function load(page = 1) {
  const current = ++generation
  loading.value = true; error.value = ''
  try { const result = await fetchAgentEvents({ ...props.scope, page, pageSize: 20 }); if (current === generation) { history.value = result; loaded.value = true } }
  catch (e) { if (current === generation) error.value = e instanceof Error ? e.message : '历史加载失败。' }
  finally { if (current === generation) loading.value = false }
}
function toggle(event: Event) { open.value = (event.target as HTMLDetailsElement).open; if (open.value && !loaded.value) load() }
watch(() => [JSON.stringify(props.scope), props.events[0]?.id], () => { generation++; loaded.value = false; if (open.value) load() })
</script>
<template>
  <details class="agent-history" @toggle="toggle"><summary>状态轨迹{{ loaded ? `（${history.total}）` : '' }}</summary>
    <p v-if="error" class="form-error" role="alert">{{ error }} <button type="button" class="text-button" @click="load()">重试</button></p><p v-if="loading" class="muted-copy" role="status">加载历史…</p>
    <p v-if="!events.length" class="muted-copy">暂无记录。</p>
    <ol v-else class="agent-timeline"><li v-for="event in events" :key="event.id"><div><strong>{{ agentEventLabel(event.eventCode) }}</strong><time>{{ agentDateLabel(event.createdAt) }}</time></div><code v-if="event.inventoryCode">{{ event.inventoryCode }}</code><p v-if="event.note">{{ event.note }}</p></li></ol>
    <AgentPagination v-if="loaded" v-bind="history" :disabled="loading" @change="load" />
  </details>
</template>
