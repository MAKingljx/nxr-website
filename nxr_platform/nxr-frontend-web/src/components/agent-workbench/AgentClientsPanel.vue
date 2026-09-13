<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  agentAddressLabel, agentDateLabel, agentStatusLabel, createAgentClient, emptyAgentClient, emptyAgentPage,
  fetchAgentClient, fetchAgentClients, updateAgentClient, useAgentActions,
  type AgentClient, type AgentClientDetail, type AgentClientInput,
} from '../../lib/agentWorkbench'
import AgentAddressFields from './AgentAddressFields.vue'
import AgentPagination from './AgentPagination.vue'
import AgentTimeline from './AgentTimeline.vue'

const emit = defineEmits<{ navigate: [tab: 'intakes' | 'returns', clientId: number] }>()
const clients = ref(emptyAgentPage<AgentClient>()), detail = ref<AgentClientDetail | null>(null)
const query = ref(''), active = ref('true'), loading = ref(false), detailLoading = ref(false)
const editing = ref(false), editId = ref<number | null>(null), form = ref<AgentClientInput>(emptyAgentClient())
const { busy, error, success, run } = useAgentActions()
let listGeneration = 0, detailGeneration = 0
async function load(page = 1) {
  const generation = ++listGeneration
  loading.value = true
  try {
    const result = await fetchAgentClients({ page, pageSize: 20, query: query.value.trim(), active: active.value || undefined })
    if (generation === listGeneration) clients.value = result
  } catch (e) { if (generation === listGeneration) error.value = e instanceof Error ? e.message : '客户列表加载失败。' }
  finally { if (generation === listGeneration) loading.value = false }
}
async function selectClient(id: number) {
  if (busy.value) return
  const generation = ++detailGeneration
  detailLoading.value = true; editing.value = false; error.value = ''; success.value = ''
  try { const result = await fetchAgentClient(id); if (generation === detailGeneration) detail.value = result }
  catch (e) { if (generation === detailGeneration) error.value = e instanceof Error ? e.message : '客户详情加载失败。' }
  finally { if (generation === detailGeneration) detailLoading.value = false }
}
function clientInput(client?: AgentClient) {
  const blank = emptyAgentClient()
  if (client) Object.keys(blank).forEach(key => { const typedKey = key as keyof AgentClientInput; Object.assign(blank, { [typedKey]: client[typedKey] ?? blank[typedKey] }) })
  return blank
}
function edit(client?: AgentClient) {
  detailGeneration++; detailLoading.value = false
  editId.value = client?.id ?? null
  form.value = clientInput(client); editing.value = true; error.value = ''; success.value = ''
}
async function save() {
  const payload = { ...form.value }
  const id = editId.value
  const generation = ++detailGeneration
  let savedId = 0
  const saved = await run(id ? `client:${id}` : 'client:create', payload, key => id ? updateAgentClient(id, payload) : createAgentClient(payload, key), value => {
    savedId = value.id
    editing.value = false
    detail.value = { client: value, intakes: detail.value?.client.id === value.id ? detail.value.intakes : [], shipments: detail.value?.client.id === value.id ? detail.value.shipments : [], events: detail.value?.client.id === value.id ? detail.value.events : [] }
  }, '客户档案已保存。')
  if (saved) {
    await load()
    try { const value = await fetchAgentClient(savedId); if (generation === detailGeneration) detail.value = value }
    catch { if (generation === detailGeneration) error.value = '档案已保存，历史记录加载失败，请重新选择客户。' }
  }
}
async function toggleActive() {
  const client = detail.value?.client
  if (!client) return
  const payload = { ...clientInput(client), active: !client.active }
  const saved = await run(`client:${client.id}:active`, payload, () => updateAgentClient(client.id, payload), value => { if (detail.value) detail.value.client = value }, payload.active ? '客户已恢复。' : '客户已归档，历史记录继续保留。')
  if (saved) await load(clients.value.page)
}
onMounted(() => load())
</script>

<template>
  <div class="agent-panel" data-testid="agent-clients-panel">
    <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="success" class="agent-success" role="status">{{ success }}</p>
    <div class="agent-split">
      <aside class="agent-list-pane">
        <div class="agent-toolbar"><h2>客户档案</h2><button type="button" class="btn-primary" :disabled="busy" data-testid="agent-new-client" @click="edit()">新增客户</button></div>
        <form class="portal-form agent-search" @submit.prevent="load()"><label>搜索客户<input v-model="query" placeholder="编号、姓名或联系方式" /></label><div class="agent-toolbar"><select v-model="active" aria-label="客户状态" @change="load()"><option value="true">使用中</option><option value="false">已归档</option><option value="">全部客户</option></select><button class="btn-secondary" :disabled="loading">搜索</button></div></form>
        <p v-if="loading" class="muted-copy" role="status">加载中…</p>
        <p v-else-if="!clients.items.length" class="portal-empty">暂无客户。</p>
        <div class="agent-record-list"><button v-for="client in clients.items" :key="client.id" type="button" class="agent-record" :class="{ selected: detail?.client.id === client.id && !editing }" :disabled="busy" @click="selectClient(client.id)"><strong>{{ client.displayName }}</strong><span>{{ client.reference }} · {{ client.phone || client.email || '—' }}</span><small v-if="!client.active">已归档</small></button></div>
        <AgentPagination v-bind="clients" :disabled="loading || busy" @change="load" />
      </aside>
      <section class="agent-detail-pane">
        <form v-if="editing" class="portal-form" data-testid="agent-client-form" @submit.prevent="save"><div class="agent-toolbar"><h2>{{ editId ? '编辑客户' : '新增客户' }}</h2><button type="button" class="text-button" :disabled="busy" @click="editing = false">取消</button></div><fieldset :disabled="busy"><div class="form-grid"><label>客户编号<input v-model="form.reference" required maxlength="64" data-testid="agent-client-reference" /></label><label>客户姓名<input v-model="form.displayName" required maxlength="128" data-testid="agent-client-name" /></label><label class="form-wide">电子邮箱（选填）<input v-model="form.email" type="email" maxlength="191" /></label></div><AgentAddressFields v-model="form" :required="false" /><p class="muted-copy">收货地址可在客户回寄前补齐。</p><label>备注<textarea v-model="form.notes" maxlength="2000" rows="3" /></label></fieldset><button class="btn-primary" :disabled="busy" data-testid="agent-save-client">{{ busy ? '保存中…' : '保存客户' }}</button></form>
        <p v-else-if="detailLoading" class="portal-empty" role="status">加载客户详情…</p>
        <template v-else-if="detail"><div class="agent-toolbar"><div><h2>{{ detail.client.displayName }}</h2><p class="muted-copy">{{ detail.client.reference }} · {{ detail.client.active ? '使用中' : '已归档' }}</p></div><button type="button" class="btn-secondary" :disabled="busy" @click="edit(detail.client)">编辑</button></div><dl class="agent-facts"><div><dt>联系方式</dt><dd>{{ [detail.client.phone, detail.client.email].filter(Boolean).join(' · ') || '未填写' }}</dd></div><div><dt>收货地址</dt><dd>{{ [detail.client.contactName, agentAddressLabel(detail.client)].filter(Boolean).join(' · ') || '待补充' }}</dd></div><div v-if="detail.client.notes"><dt>备注</dt><dd>{{ detail.client.notes }}</dd></div></dl><div class="agent-actions"><button type="button" class="btn-primary" @click="emit('navigate', 'intakes', detail.client.id)">客户来件</button><button type="button" class="btn-secondary" @click="emit('navigate', 'returns', detail.client.id)">查看回寄</button><button type="button" class="text-button" :disabled="busy" @click="toggleActive">{{ detail.client.active ? '归档客户' : '恢复客户' }}</button></div>
          <h3>最近来件（{{ detail.intakes.length }}）</h3><p v-if="!detail.intakes.length" class="muted-copy">暂无来件。</p><div v-else class="table-scroll"><table class="portal-table"><thead><tr><th>来件</th><th>卡片</th><th>状态</th><th>送评订单</th></tr></thead><tbody><tr v-for="intake in detail.intakes" :key="intake.id"><td>{{ intake.intakeNo }}<small>{{ agentDateLabel(intake.createdAt) }}</small></td><td>{{ intake.checkedInCardCount }} / {{ intake.expectedCardCount }}</td><td>{{ agentStatusLabel(intake.statusCode) }}</td><td><router-link v-if="intake.orderNo" :to="`/account/orders/${intake.orderNo}`">{{ intake.orderNo }}</router-link><span v-else>—</span></td></tr></tbody></table></div>
          <h3>最近回寄（{{ detail.shipments.length }}）</h3><p v-if="!detail.shipments.length" class="muted-copy">暂无回寄。</p><div v-else class="table-scroll"><table class="portal-table"><thead><tr><th>回寄单</th><th>物流</th><th>卡片</th><th>状态</th></tr></thead><tbody><tr v-for="shipment in detail.shipments" :key="shipment.id"><td>{{ shipment.shipmentNo }}</td><td>{{ shipment.carrierName }}<small>{{ shipment.trackingNumber }}</small></td><td>{{ shipment.cardCount }}</td><td>{{ agentStatusLabel(shipment.statusCode) }}</td></tr></tbody></table></div><AgentTimeline :events="detail.events" :scope="{ clientId: detail.client.id }" />
        </template>
        <p v-else class="portal-empty">选择客户查看档案和历史，或新增客户。</p>
      </section>
    </div>
  </div>
</template>
