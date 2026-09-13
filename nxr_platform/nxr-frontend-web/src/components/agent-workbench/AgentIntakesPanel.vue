<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { fetchApplicationConfig } from '../../lib/orderApplication'
import { createAgentIntakeLabelPrinter } from '../../lib/agentIntakeLabels'
import PrivateOrderPhoto from '../PrivateOrderPhoto.vue'
import {
  agentDateLabel, agentStatusLabel, checkInAgentCard, createAgentIntake, emptyAgentPage, fetchAgentIntake,
  fetchAgentIntakes, receiveAgentIntake, uploadAgentCardPhoto, useAgentActions,
  type AgentCard, type AgentIntake, type AgentIntakeDetail, type AgentSubmission,
} from '../../lib/agentWorkbench'
import AgentClientPicker from './AgentClientPicker.vue'
import AgentPagination from './AgentPagination.vue'
import AgentSubmissionForm from './AgentSubmissionForm.vue'
import AgentTimeline from './AgentTimeline.vue'

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
  } catch (e) { if (current === listGeneration) error.value = e instanceof Error ? e.message : '来件列表加载失败。' }
  finally { if (current === listGeneration) loading.value = false }
}
async function show(id: number) {
  if (busy.value) return
  const current = ++detailGeneration
  detailLoading.value = true; mode.value = 'detail'; scan.inventoryCode = ''; scan.conditionNote = ''; scan.hasException = false; error.value = ''; success.value = ''
  try { const result = await fetchAgentIntake(id); if (current === detailGeneration) detail.value = result }
  catch (e) { if (current === detailGeneration) error.value = e instanceof Error ? e.message : '来件详情加载失败。' }
  finally { if (current === detailGeneration) detailLoading.value = false }
}
function newIntake() { detailGeneration++; detailLoading.value = false; mode.value = 'new'; createdSubmission.value = null; error.value = ''; success.value = ''; if (clientFilter.value) form.clientId = clientFilter.value }
function addCard() { if (cards.value.length < maxCards.value) cards.value.push({ localId: crypto.randomUUID(), cardName: '', languageCode: 'EN', notes: '' }) }
function fillExpected() { if (Number.isInteger(form.expectedCardCount) && form.expectedCardCount <= maxCards.value) while (cards.value.length < form.expectedCardCount) addCard() }
async function create() {
  if (cards.value.length !== form.expectedCardCount) { error.value = '逐卡登记数量须与预期数量一致。'; return }
  const payload = { ...form, cards: cards.value.map(({ cardName, languageCode, notes }) => ({ cardName: cardName.trim(), languageCode: languageCode.toUpperCase().trim(), notes })) }
  const saved = await run('intake:create', payload, key => createAgentIntake(payload, key), value => {
    detail.value = value; mode.value = 'detail'
    Object.assign(form, { carrierName: '', trackingNumber: '', expectedCardCount: 1, notes: '' }); cards.value = [{ localId: crypto.randomUUID(), cardName: '', languageCode: 'EN', notes: '' }]
  }, '来件已登记，库存码已生成。')
  if (saved) await load()
}
async function receive() {
  const id = detail.value?.intake.id
  if (!id) return
  const payload = { note: receiveNote.value }
  const saved = await run(`intake:${id}:receive`, payload, key => receiveAgentIntake(id, payload, key), value => { detail.value = value; receiveNote.value = '' }, '已签收，请逐卡扫描清点。')
  if (saved) { await load(rows.value.page); await nextTick(); scanInput.value?.focus() }
}
async function checkIn() {
  const id = detail.value?.intake.id
  if (!id || !canScan.value || !scan.inventoryCode.trim()) return
  if (scan.hasException && !scan.conditionNote.trim()) { error.value = '请填写异常情况。'; return }
  const payload = { inventoryCode: scan.inventoryCode.trim(), conditionNote: scan.conditionNote.trim(), hasException: scan.hasException }
  const saved = await run(`intake:${id}:check-in`, payload, key => checkInAgentCard(id, payload, key), value => { detail.value = value; scan.inventoryCode = ''; scan.conditionNote = ''; scan.hasException = false }, payload.hasException ? '异常已记录；解决后请再次扫码复核。' : '卡片已核对入库。')
  if (saved) await load(rows.value.page)
  await nextTick(); scanInput.value?.focus(); if (!saved) scanInput.value?.select()
}
async function upload(card: AgentCard, side: 'front' | 'back', event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]
  if (!file || busy.value) return
  if (!['image/jpeg', 'image/png'].includes(file.type) || file.size > 15 * 1024 * 1024) { error.value = '照片请选择 15 MB 以内的 JPG 或 PNG。'; input.value = ''; return }
  const saved = await run(`card:${card.id}:photo:${side}`, { name: file.name, size: file.size, modified: file.lastModified }, () => uploadAgentCardPhoto(card.id, side, file), () => {}, '照片已保存。')
  input.value = ''
  if (saved && detail.value) { try { detail.value = await fetchAgentIntake(detail.value.intake.id) } catch { error.value = '照片已保存，预览加载失败，请重新选择来件。' } }
}
function select(intake: AgentIntake, event: Event) {
  if ((event.target as HTMLInputElement).checked) selected.value[intake.id] = intake
  else delete selected.value[intake.id]
}
async function submitted(value: AgentSubmission) { createdSubmission.value = value; selected.value = {}; mode.value = 'detail'; detail.value = null; await load() }
function printLabels() {
  if (!detail.value || busy.value) return
  try { labelPrinter.open(detail.value.intake, detail.value.cards) }
  catch (e) { error.value = e instanceof Error ? e.message : '标签预览无法打开，请重试。' }
}
onMounted(async () => { await load(); try { maxCards.value = (await fetchApplicationConfig()).maxCardsPerOrder } catch (e) { error.value = e instanceof Error ? e.message : '卡片数量限制加载失败。' } })
onBeforeUnmount(labelPrinter.close)
</script>

<template>
  <div class="agent-panel" data-testid="agent-intakes-panel">
    <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="success" class="agent-success" role="status">{{ success }}</p>
    <div v-if="createdSubmission" class="agent-success" role="status">已生成送评批次 <strong>{{ createdSubmission.batchNo }}</strong>。<router-link :to="{ path: '/account/merchant-orders', query: { batch: createdSubmission.batchNo } }" data-testid="agent-open-batch">前往批次订单</router-link></div>
    <div class="agent-split">
      <aside class="agent-list-pane"><div class="agent-toolbar"><h2>来件与库存</h2><button type="button" class="btn-primary" :disabled="busy" data-testid="agent-new-intake" @click="newIntake">登记来件</button></div>
        <form class="portal-form agent-search" @submit.prevent="load()"><label>搜索来件<input v-model="query" placeholder="来件编号或运单号" /></label><div class="agent-toolbar"><select v-model="statusCode" aria-label="来件状态" @change="load()"><option value="">全部状态</option><option value="expected">待签收</option><option value="received">待清点</option><option value="exception">有异常</option><option value="ready">已齐全入库</option><option value="submitted">已送评</option></select><button class="btn-secondary" :disabled="loading">搜索</button></div><button v-if="clientFilter" type="button" class="text-button" @click="clientFilter = 0; load()">取消客户筛选</button></form>
        <p v-if="loading" class="muted-copy" role="status">加载中…</p><p v-else-if="!rows.items.length" class="portal-empty">暂无来件。</p>
        <div class="agent-record-list"><div v-for="intake in rows.items" :key="intake.id" class="agent-intake-row"><label v-if="intake.statusCode === 'ready'" class="agent-select-record"><input type="checkbox" :checked="Boolean(selected[intake.id])" :disabled="busy" :aria-label="`选择来件 ${intake.intakeNo}`" :data-testid="`agent-select-intake-${intake.id}`" @change="select(intake, $event)" /></label><button type="button" class="agent-record" :class="{ selected: detail?.intake.id === intake.id && mode === 'detail' }" :disabled="busy" @click="show(intake.id)"><strong>{{ intake.intakeNo }}</strong><span>{{ intake.clientName }} · {{ intake.checkedInCardCount }} / {{ intake.expectedCardCount }} 张</span><span>{{ agentStatusLabel(intake.statusCode) }}</span></button></div></div><AgentPagination v-bind="rows" :disabled="loading || busy" @change="load" />
        <div v-if="selectedIntakes.length" class="agent-selection-bar"><span>已选 {{ selectedIntakes.length }} 件完整来件</span><button type="button" class="btn-primary" :disabled="busy" data-testid="agent-start-submission" @click="mode = 'submission'">生成送评批次</button><button type="button" class="text-button" :disabled="busy" @click="selected = {}">清空选择</button></div>
      </aside>
      <section class="agent-detail-pane">
        <form v-if="mode === 'new'" class="portal-form" data-testid="agent-intake-form" @submit.prevent="create"><div class="agent-toolbar"><h2>登记客户来件</h2><button type="button" class="text-button" :disabled="busy" @click="mode = 'detail'">取消</button></div><fieldset :disabled="busy"><AgentClientPicker v-model="form.clientId" :disabled="busy" /><div class="form-grid"><label>快递公司<input v-model="form.carrierName" required maxlength="128" data-testid="agent-intake-carrier" /></label><label>运单号<input v-model="form.trackingNumber" required maxlength="255" data-testid="agent-intake-tracking" /></label><label>预期卡片数量（最多 {{ maxCards }} 张）<input v-model.number="form.expectedCardCount" type="number" min="1" :max="maxCards" required data-testid="agent-intake-quantity" /></label><button type="button" class="btn-secondary agent-align-bottom" @click="fillExpected">补齐卡片录入行</button></div><label>来件备注<textarea v-model="form.notes" maxlength="2000" rows="2" /></label><div class="agent-toolbar"><h3>逐卡登记 · {{ cards.length }} 张</h3><button type="button" class="btn-secondary" :disabled="cards.length >= maxCards" data-testid="agent-add-card" @click="addCard">添加卡片</button></div><div v-for="(card, index) in cards" :key="card.localId" class="agent-card-entry"><div class="agent-toolbar"><strong>卡片 {{ index + 1 }}</strong><button type="button" class="text-button" :disabled="cards.length <= 1" @click="cards.splice(index, 1)">移除</button></div><div class="form-grid"><label>卡名<input v-model="card.cardName" required maxlength="255" :data-testid="`agent-card-name-${index}`" /></label><label>语言<input v-model="card.languageCode" required maxlength="32" placeholder="EN / ZH / JA" :data-testid="`agent-card-language-${index}`" /></label></div><label>卡片备注<input v-model="card.notes" maxlength="2000" /></label></div></fieldset><p v-if="cards.length !== form.expectedCardCount" class="muted-copy">还需使登记数量与预期 {{ form.expectedCardCount }} 张一致。</p><button class="btn-primary" :disabled="busy || cards.length !== form.expectedCardCount || !form.clientId" data-testid="agent-create-intake">{{ busy ? '登记中…' : '登记并生成库存码' }}</button></form>
        <AgentSubmissionForm v-else-if="mode === 'submission'" :intakes="selectedIntakes" @created="submitted" @close="mode = 'detail'" />
        <p v-else-if="detailLoading" class="portal-empty" role="status">加载来件详情…</p>
        <template v-else-if="detail"><div class="agent-toolbar"><div><h2>{{ detail.intake.intakeNo }}</h2><p class="muted-copy">{{ detail.intake.clientName }} · {{ agentStatusLabel(detail.intake.statusCode) }}</p></div><span class="status-pill">{{ detail.intake.checkedInCardCount }} / {{ detail.intake.expectedCardCount }} 张已清点</span></div><dl class="agent-facts"><div><dt>客户来件</dt><dd>{{ detail.intake.carrierName }} · {{ detail.intake.trackingNumber }}</dd></div><div><dt>签收时间</dt><dd>{{ agentDateLabel(detail.intake.receivedAt) }}</dd></div><div v-if="detail.intake.notes"><dt>备注</dt><dd>{{ detail.intake.notes }}</dd></div><div v-if="detail.intake.orderNo"><dt>送评订单</dt><dd><router-link :to="`/account/orders/${detail.intake.orderNo}`">{{ detail.intake.orderNo }}</router-link> · <router-link to="/account/merchant-orders">{{ detail.intake.batchNo }}</router-link></dd></div></dl>
          <form v-if="detail.intake.statusCode === 'expected'" class="portal-form agent-operation" @submit.prevent="receive"><label>签收备注（选填）<input v-model="receiveNote" maxlength="2000" :disabled="busy" /></label><button class="btn-primary" :disabled="busy" data-testid="agent-receive-intake">确认签收来件</button></form>
          <form v-if="canScan" class="portal-form agent-operation" @submit.prevent="checkIn"><h3>逐卡清点</h3><label>扫描或输入库存码<input ref="scanInput" v-model="scan.inventoryCode" required autocomplete="off" autocapitalize="off" spellcheck="false" :disabled="busy" placeholder="扫描后按回车" data-testid="agent-check-in-code" /></label><label class="agent-checkbox"><input v-model="scan.hasException" type="checkbox" :disabled="busy" data-testid="agent-check-in-exception" />此卡存在异常</label><label>{{ scan.hasException ? '异常情况' : '清点备注（选填）' }}<input v-model="scan.conditionNote" maxlength="2000" :required="scan.hasException" :disabled="busy" data-testid="agent-check-in-note" /></label><button class="btn-primary" :disabled="busy" data-testid="agent-check-in-submit">{{ busy ? '核对中…' : scan.hasException ? '登记异常' : '核对入库' }}</button><p v-if="detail.intake.exceptionCardCount" class="muted-copy">{{ detail.intake.exceptionCardCount }} 张卡存在异常；处理后取消异常勾选，重新扫码复核。</p></form>
          <p v-if="detail.intake.statusCode === 'ready'" class="agent-success">卡片已齐全入库，可在左侧勾选来件生成送评批次。</p>
          <div class="agent-toolbar"><h3>卡片库存（{{ detail.cards.length }}）</h3><button type="button" class="btn-secondary" :disabled="busy || !detail.cards.length" data-testid="agent-print-intake-labels" @click="printLabels">打印收卡标签</button></div><div class="agent-inventory-cards"><article v-for="card in detail.cards" :key="card.id" class="agent-stock-card" :data-testid="`agent-stock-card-${card.id}`"><div class="agent-toolbar"><strong>{{ card.cardName }}</strong><span class="status-pill">{{ card.statusCode === 'expected' ? '待清点' : agentStatusLabel(card.statusCode) }}</span></div><code class="agent-inventory-code" :data-testid="`agent-inventory-code-${card.id}`">{{ card.inventoryCode }}</code><p class="muted-copy">{{ card.languageCode }}<span v-if="card.notes"> · {{ card.notes }}</span></p><p v-if="card.conditionNote" :class="card.statusCode === 'exception' ? 'form-error' : 'muted-copy'">{{ card.conditionNote }}</p><div><PrivateOrderPhoto :photo-id="card.frontPhotoId" label="正面" /><PrivateOrderPhoto :photo-id="card.backPhotoId" label="背面" /></div><div v-if="editableCards" class="agent-photo-controls"><label>正面照片<input type="file" accept="image/jpeg,image/png" :disabled="busy" :data-testid="`agent-photo-front-${card.id}`" @change="upload(card, 'front', $event)" /></label><label>背面照片<input type="file" accept="image/jpeg,image/png" :disabled="busy" @change="upload(card, 'back', $event)" /></label></div></article></div><AgentTimeline :events="detail.events" :scope="{ intakeId: detail.intake.id }" />
        </template>
        <p v-else class="portal-empty">选择来件进行签收、清点和库存核对。</p>
      </section>
    </div>
  </div>
</template>
