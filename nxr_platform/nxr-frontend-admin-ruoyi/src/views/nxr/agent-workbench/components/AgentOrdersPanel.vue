<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { tx, localizeBackendMessage, localizeOrderEventDetail } from '@/i18n'
import { workspacePath } from '@/utils/submissionWorkspace'
import { ADMIN_BATCHES_PATH, ADMIN_ORDERS_PATH } from '@/utils/adminOrderNavigation'
import { useAgentApi, useAgentActions, emptyAgentAddress, emptyAgentPage, agentDateLabel, agentStatusLabel, agentFulfillmentStatusLabel, privateTrackingUrl, type WorkbenchOrder, type WorkbenchOrderDetail, type WorkbenchOrderOptions, type AgentOrder } from '../lib/agentWorkbench'
import { addressLabel, canCompleteExistingIntake, displayedCardStatus, draftFingerprint, orderReturnProgress, orderSelection, submissionOrderIds, validateOrderDraft } from '../lib/workbenchOrders'
import AgentPagination from './AgentPagination.vue'
import AgentOrderNumberLink from './AgentOrderNumberLink.vue'
import AgentAddressFields from './AgentAddressFields.vue'
import AgentPrivatePhoto from './AgentPrivatePhoto.vue'
import AgentTimeline from './AgentTimeline.vue'
import AgentIntakesPanel from './AgentIntakesPanel.vue'
import AgentPartnerReturns from './AgentPartnerReturns.vue'
const props = defineProps<{ initialRecordId?: number; detailOnly?: boolean }>()
const emit = defineEmits<{ closed: [] }>()
const api = useAgentApi(), route = useRoute(), router = useRouter(), { busy, error, success, run } = useAgentActions()
const rows = ref(emptyAgentPage<WorkbenchOrder>()), query = ref(''), status = ref('')
const loading = ref(false), detailLoading = ref(false), detail = ref<WorkbenchOrderDetail | null>(null)
const selected = ref<Record<number, WorkbenchOrder>>({}), selectedOrders = computed<WorkbenchOrder[]>(() => orderSelection(selected.value))
const options = ref<WorkbenchOrderOptions | null>(null), optionsLoading = ref(false)
const mode = ref<'new' | 'detail' | ''>(''), photoCard = ref<number | null>(null), photoProgress = ref('')
const trackingLink = ref(''), legacyIntakeId = ref<number | null>(null)
const fulfillmentOrder = ref<AgentOrder | null>(null), timelineLoading = ref(false), timelineError = ref('')
type Photo = { file: File | null; id: number | null; url: string }
type CardDraft = { localId: string; cardName: string; brandName: string; setName: string; officialCardNumber: string; languageCode: string; productType: string; category: string; front: Photo; back: Photo }
const newCard = (): CardDraft => ({ localId: crypto.randomUUID(), cardName: '', brandName: '', setName: '', officialCardNumber: '', languageCode: '', productType: options.value?.productTypes[0]?.value || '', category: '', front: { file: null, id: null, url: '' }, back: { file: null, id: null, url: '' } })
const draft = reactive({ clientName: '', phone: '', email: '', notes: '', customerAddress: emptyAgentAddress(), cards: [] as CardDraft[] })
const drawerTitle = computed(() => mode.value === 'new' ? tx('New Order') : detail.value?.order.orderNo || tx('Order'))
let listGeneration = 0, detailGeneration = 0
function orderStatus(row: WorkbenchOrder) {
  if (row.statusCode === 'ready') return tx('Ready to submit')
  if (row.statusCode === 'delivered') return tx('Delivered to partner')
  if (['outbound_shipped', 'return_shipped'].includes(row.statusCode)) return tx('Returning to partner')
  return row.gradingOrderId ? agentFulfillmentStatusLabel(row.statusCode) : agentStatusLabel(row.statusCode)
}
async function load(page = 1) {
  const current = ++listGeneration
  loading.value = true; error.value = ''
  try { const value = await api.fetchWorkbenchOrders({ page, pageSize: 20, query: query.value.trim(), statusCode: status.value }); if (current === listGeneration) rows.value = value }
  catch (e) { if (current === listGeneration) error.value = e instanceof Error ? e.message : tx('Unable to load orders.') }
  finally { if (current === listGeneration) loading.value = false }
}
async function loadOptions() {
  optionsLoading.value = true
  try { options.value = await api.fetchWorkbenchOrderOptions(); for (const card of draft.cards) if (!card.productType) card.productType = options.value.productTypes[0]?.value || '' }
  catch (e) { error.value = e instanceof Error ? e.message : tx('Unable to load card options.') }
  finally { optionsLoading.value = false }
}
function releasePhoto(photo: Photo) { if (photo.url) URL.revokeObjectURL(photo.url) }
function clearDraft() { for (const card of draft.cards) { releasePhoto(card.front); releasePhoto(card.back) }; Object.assign(draft, { clientName: '', phone: '', email: '', notes: '', customerAddress: emptyAgentAddress(), cards: [newCard()] }) }
function newOrder() { clearDraft(); error.value = ''; success.value = ''; mode.value = 'new'; if (!options.value) void loadOptions() }
async function canLeaveDraft() {
  if (busy.value) return false
  const dirty = draft.clientName || draft.phone || draft.email || draft.notes || Object.values(draft.customerAddress).some(Boolean) || draft.cards.some(card => card.cardName || card.brandName || card.setName || card.officialCardNumber || card.languageCode || card.front.file || card.back.file)
  if (mode.value !== 'new' || !dirty) return true
  try { await ElMessageBox.confirm(tx('Discard the unsaved order and photos?'), tx('Close order'), { confirmButtonText: tx('Discard'), cancelButtonText: tx('Continue editing'), type: 'warning' }); return true } catch { return false }
}
async function close(done?: () => void) {
  if (!await canLeaveDraft()) return
  if (mode.value === 'new') clearDraft()
  mode.value = ''; detailGeneration++; detailLoading.value = false; done?.()
  emit('closed')
}
function addCard() { if (draft.cards.length < (options.value?.maxCardsPerOrder || 1)) draft.cards.push(newCard()) }
function removeCard(index: number) { if (draft.cards.length < 2) return; const [card] = draft.cards.splice(index, 1); releasePhoto(card.front); releasePhoto(card.back) }
function selectPhoto(card: CardDraft, side: 'front' | 'back', event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]
  if (!file) return
  if (!['image/jpeg', 'image/png'].includes(file.type) || file.size > 15 * 1024 * 1024) { error.value = tx('Choose a JPG or PNG file no larger than 15 MB.'); input.value = ''; return }
  releasePhoto(card[side]); card[side] = { file, id: null, url: URL.createObjectURL(file) }; error.value = ''
}
async function create() {
  if (!options.value) { error.value = tx('Unable to load card options.'); return }
  const validation = validateOrderDraft(draft, options.value.maxCardsPerOrder)
  if (validation) { error.value = tx(validation); return }
  const fingerprint = draftFingerprint(draft)
  const saved = await run('workbench-order:create', fingerprint, async key => {
    // Successful uploads remain attached to this draft when another upload or the save fails.
    for (const [index, card] of draft.cards.entries()) {
      for (const side of ['front', 'back'] as const) {
        if (!card[side].id && card[side].file) {
          photoProgress.value = tx('Uploading card {number}: {side}', { number: index + 1, side: tx(side === 'front' ? 'Front' : 'Back') })
          const uploaded = await api.uploadSupplementalPhoto(card[side].file!)
          card[side].id = uploaded.id
        }
      }
    }
    photoProgress.value = tx('Saving order…')
    return api.createWorkbenchOrder({ clientName: draft.clientName.trim(), phone: draft.phone.trim(), email: draft.email.trim(), notes: draft.notes.trim(), customerAddress: { ...draft.customerAddress, contactName: draft.clientName.trim(), phone: draft.phone.trim() }, cards: draft.cards.map(({ localId, front, back, ...card }) => ({ ...card, frontPhotoId: front.id!, backPhotoId: back.id! })) }, key)
  }, value => { detail.value = value; mode.value = 'detail'; clearDraft() }, tx('Order created.'))
  photoProgress.value = ''
  if (saved) await load()
}
async function show(id: number) {
  const current = ++detailGeneration
  mode.value = 'detail'; detail.value = null; fulfillmentOrder.value = null; timelineLoading.value = false; timelineError.value = ''; detailLoading.value = true; photoCard.value = null; trackingLink.value = ''; error.value = ''
  try { const value = await api.fetchWorkbenchOrder(id); if (current === detailGeneration) { detail.value = value; if (value.order.gradingOrderNo) void loadFulfillment(value.order.gradingOrderNo, current) } }
  catch (e) { if (current === detailGeneration) error.value = e instanceof Error ? e.message : tx('Unable to load order details.') }
  finally { if (current === detailGeneration) detailLoading.value = false }
}
async function loadFulfillment(orderNo: string, current = detailGeneration) {
  timelineLoading.value = true; timelineError.value = ''
  try { const value = await api.fetchOrder(orderNo); if (current === detailGeneration) fulfillmentOrder.value = value }
  catch (e) { if (current === detailGeneration) timelineError.value = e instanceof Error ? e.message : tx('Unable to load order progress.') }
  finally { if (current === detailGeneration) timelineLoading.value = false }
}
async function closeLegacyIntake() {
  const id = legacyIntakeId.value
  legacyIntakeId.value = null
  if (id) { await show(id); await load(rows.value.page) }
}
function selectOrder(row: WorkbenchOrder, event: Event) { if ((event.target as HTMLInputElement).checked) selected.value[row.id] = row; else delete selected.value[row.id] }
async function selectForSubmit() {
  if (!selectedOrders.value.length || busy.value) return
  const ids = submissionOrderIds(selectedOrders.value.map(order => order.id))
  await router.push({ path: workspacePath('batches'), query: { company: String(api.companyId), action: 'create', orders: ids.join(',') } })
}
async function viewSubmit() {
  if (!detail.value?.order.batchNo) return
  if (route.meta.adminUnified && detail.value.order.batchId) {
    return router.push({ path: ADMIN_BATCHES_PATH, query: { batchId: String(detail.value.order.batchId) } })
  }
  await router.push({ path: workspacePath('batches'), query: { company: String(api.companyId), batch: detail.value.order.batchNo, ...(detail.value.order.gradingOrderNo ? { order: detail.value.order.gradingOrderNo } : {}) } })
}
function openGradingOrder() {
  if (detail.value?.order.gradingOrderId) {
    void router.push({ path: ADMIN_ORDERS_PATH, query: { orderId: String(detail.value.order.gradingOrderId) } })
  }
}
async function createTrackingLink() {
  const order = detail.value?.order
  if (!order?.batchNo || !order.gradingOrderNo) return
  await run(`tracking:${order.id}`, { orderId: order.id }, () => api.rotateTrackingLink(order.batchNo!, order.gradingOrderNo!), value => { trackingLink.value = privateTrackingUrl(value.trackingUrl) }, tx('Customer tracking link updated. The previous link is no longer valid.'))
}
async function copyLink() { try { await navigator.clipboard.writeText(trackingLink.value); success.value = tx('Customer tracking link copied.') } catch { error.value = tx('The browser did not allow copying. Select the link and copy it manually.') } }
onBeforeRouteLeave(canLeaveDraft)
onBeforeRouteUpdate((to, from) => to.path !== from.path || to.query.company !== from.query.company ? canLeaveDraft() : true)
onMounted(async () => { if (!props.detailOnly) await load(); if (props.initialRecordId) await show(props.initialRecordId) })
onBeforeUnmount(() => { listGeneration++; detailGeneration++; for (const card of draft.cards) { releasePhoto(card.front); releasePhoto(card.back) } })
</script>

<template>
  <section class="order-panel" data-testid="agent-orders-panel">
    <template v-if="!detailOnly">
    <div class="agent-toolbar"><div class="order-total">{{ $tx('{count} orders', { count: rows.total }) }}</div><div class="order-toolbar-actions"><el-button type="primary" :disabled="busy" data-testid="agent-new-order" @click="newOrder">{{ $tx('New Order') }}</el-button></div></div>
    <el-alert v-if="error && !mode" :title="localizeBackendMessage(error)" type="error" :closable="false" />
    <el-alert v-if="success && !mode" :title="localizeBackendMessage(success)" type="success" :closable="false" />
    <form class="agent-list-filters" @submit.prevent="load()">
      <label class="agent-filter-field agent-filter-grow">{{ $tx('Search orders') }}<input v-model="query" :placeholder="$tx('Order ID, customer or card name')" :disabled="busy" data-testid="agent-order-query" /></label>
      <label class="agent-filter-field">{{ $tx('Status') }}<select v-model="status" :disabled="busy" data-testid="agent-order-status"><option value="">{{ $tx('All statuses') }}</option><option value="ready">{{ $tx('Ready to submit') }}</option><option value="admission_review">{{ $tx('Application under review') }}</option><option value="awaiting_inbound">{{ $tx('Awaiting shipment to NXR') }}</option><option value="inbound_shipped">{{ $tx('In transit to NXR') }}</option><option value="received">{{ $tx('Received by NXR') }}</option><option value="grading">{{ $tx('Grading') }}</option><option value="completed">{{ $tx('Grading completed') }}</option><option value="quality_passed">{{ $tx('Ready for return') }}</option><option value="return_shipped">{{ $tx('Returning to partner') }}</option><option value="delivered">{{ $tx('Delivered to partner') }}</option></select></label>
      <el-button native-type="submit" :loading="loading" :disabled="busy">{{ $tx('Search') }}</el-button><el-button :disabled="busy" @click="query = ''; status = ''; load()">{{ $tx('Reset') }}</el-button>
    </form>
    <div v-if="selectedOrders.length" class="order-selection" data-testid="agent-order-selection"><span>{{ $tx('{count} orders selected', { count: selectedOrders.length }) }}</span><el-button type="primary" :disabled="busy || !selectedOrders.length" data-testid="agent-start-submit" @click="selectForSubmit">{{ $tx('New Submit') }}</el-button><el-button v-if="selectedOrders.length" link :disabled="busy" @click="selected = {}">{{ $tx('Clear selection') }}</el-button><span v-if="!selectedOrders.length" class="muted-copy">{{ $tx('Select orders ready to submit.') }}</span></div>
    <div class="agent-table-scroll" :aria-busy="loading"><table class="agent-results-table" data-testid="agent-orders-table"><thead><tr><th class="agent-select-column">{{ $tx('Select') }}</th><th>{{ $tx('Order ID') }}</th><th>{{ $tx('Customer') }}</th><th>{{ $tx('Cards') }}</th><th>{{ $tx('Status') }}</th><th>{{ $tx('Delivered / total') }}</th><th>{{ $tx('Submit') }}</th><th>{{ $tx('Created At') }}</th><th>{{ $tx('Actions') }}</th></tr></thead><tbody>
      <tr v-if="loading"><td colspan="9" class="agent-table-empty">{{ $tx('Loading…') }}</td></tr><tr v-else-if="!rows.items.length"><td colspan="9" class="agent-table-empty">{{ $tx('No matching orders.') }}</td></tr>
      <tr v-for="row in loading ? [] : rows.items" :key="row.id"><td class="agent-select-column"><input v-if="row.canSubmit" type="checkbox" :checked="Boolean(selected[row.id])" :disabled="busy" :aria-label="$tx('Select order {number}', { number: row.orderNo })" :data-testid="`agent-select-order-${row.id}`" @change="selectOrder(row, $event)" /><span v-else>—</span></td><td><AgentOrderNumberLink :order-id="row.id" :order-no="row.orderNo" :company-id="api.companyId" :disabled="busy" @preview="show(row.id)" /></td><td>{{ row.clientName }}</td><td>{{ row.cardCount }}</td><td><span class="status-pill">{{ orderStatus(row) }}</span></td><td>{{ orderReturnProgress(row) ? `${row.deliveredCardCount} / ${row.cardCount}` : '—' }}<small v-if="row.returnShippedCardCount">{{ $tx('{count} cards returning', { count: row.returnShippedCardCount }) }}</small></td><td>{{ row.batchNo || '—' }}</td><td class="agent-date-cell">{{ agentDateLabel(row.createdAt) }}</td><td><el-button link type="primary" :disabled="busy" :data-testid="`agent-view-order-${row.id}`" @click="show(row.id)">{{ $tx('View') }}</el-button></td></tr>
    </tbody></table></div>
    <AgentPagination v-bind="rows" :disabled="loading || busy" @change="load" />
    </template>
    <el-drawer :model-value="Boolean(mode)" :title="drawerTitle" size="min(1240px, 96vw)" :append-to-body="false" :before-close="close" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" destroy-on-close data-testid="agent-order-drawer">
      <el-alert v-if="error" :title="localizeBackendMessage(error)" type="error" :closable="false" />
      <el-alert v-if="success" :title="localizeBackendMessage(success)" type="success" :closable="false" />
      <form v-if="mode === 'new'" id="agent-new-order-form" class="portal-form" @submit.prevent="create">
        <p class="order-required-hint"><b class="required" aria-hidden="true">*</b> {{ $tx('Required field') }}</p>
        <fieldset :disabled="busy">
          <section class="order-form-section" data-testid="agent-order-customer-information"><h3>{{ $tx('Customer information') }}</h3><div class="form-grid"><label><span>{{ $tx('Customer name') }} <b class="required">*</b></span><input v-model="draft.clientName" required maxlength="128" autocomplete="off" data-testid="agent-order-client-name" /></label><label><span>{{ $tx('Phone number') }} <b v-if="!draft.email.trim()" class="required" aria-hidden="true">*</b></span><input v-model="draft.phone" type="tel" maxlength="64" data-testid="agent-order-phone" /></label><label><span>{{ $tx('Email') }} <b v-if="!draft.phone.trim()" class="required" aria-hidden="true">*</b></span><input v-model="draft.email" type="email" maxlength="191" data-testid="agent-order-email" /></label><label>{{ $tx('Notes') }}<input v-model="draft.notes" maxlength="2000" /></label></div><div class="customer-address-inline" data-testid="agent-order-customer-address"><AgentAddressFields v-model="draft.customerAddress" :include-contact="false" :postal-required="false" :region-required="false" /></div></section>
          <section class="order-form-section"><div class="agent-toolbar"><h3>{{ $tx('Cards') }} · {{ draft.cards.length }}</h3><el-button :disabled="busy || !options || draft.cards.length >= options.maxCardsPerOrder" data-testid="agent-order-add-card" @click="addCard">{{ $tx('Add card') }}</el-button></div>
            <p v-if="optionsLoading" role="status">{{ $tx('Loading card options…') }}</p><el-button v-if="!options && !optionsLoading" @click="loadOptions">{{ $tx('Retry') }}</el-button>
            <article v-for="(card, index) in draft.cards" :key="card.localId" class="order-card-editor" :data-testid="`agent-order-card-${index}`"><div class="agent-toolbar"><strong>{{ $tx('Card {p1}', { p1: index + 1 }) }}</strong><el-button link type="danger" :disabled="busy || draft.cards.length < 2" @click="removeCard(index)">{{ $tx('Remove') }}</el-button></div>
              <div class="order-card-grid"><label><span>{{ $tx('Product Type') }} <b class="required">*</b></span><select v-model="card.productType" required :data-testid="`agent-order-product-${index}`"><option value="" disabled>{{ $tx('Select') }}</option><option v-for="item in options?.productTypes" :key="item.value" :value="item.value">{{ $tx(item.label) }}</option></select></label><label><span>{{ $tx('Card category') }} <b class="required">*</b></span><select v-model="card.category" required :data-testid="`agent-order-category-${index}`"><option value="" disabled>{{ $tx('Select') }}</option><option v-for="item in options?.categories" :key="item.value" :value="item.value">{{ $tx(item.label) }}</option></select></label><label><span>{{ $tx('Brand') }} <b class="required">*</b></span><select v-model="card.brandName" required :data-testid="`agent-order-brand-${index}`"><option value="" disabled>{{ $tx('Select') }}</option><option v-for="item in options?.brands" :key="item.value" :value="item.value">{{ item.label }}</option></select></label><label><span>{{ $tx('Card name') }} <b class="required">*</b></span><input v-model="card.cardName" required maxlength="255" :data-testid="`agent-order-card-name-${index}`" /></label><label><span>{{ $tx('Card set') }} <b class="required">*</b></span><input v-model="card.setName" required maxlength="255" :data-testid="`agent-order-set-${index}`" /></label><label><span>{{ $tx('Official card number') }} <b class="required">*</b></span><input v-model="card.officialCardNumber" required maxlength="128" :data-testid="`agent-order-number-${index}`" /></label><label><span>{{ $tx('Language') }} <b class="required">*</b></span><select v-model="card.languageCode" required :data-testid="`agent-order-language-${index}`"><option value="" disabled>{{ $tx('Select') }}</option><option v-for="item in options?.languages" :key="item.value" :value="item.value">{{ $tx(item.label) }}</option></select></label></div>
              <div class="order-photo-grid"><label v-for="side in (['front', 'back'] as const)" :key="side" class="order-photo-upload"><span>{{ side === 'front' ? $tx('Front photo') : $tx('Back photo') }} <b class="required">*</b></span><img v-if="card[side].url" :src="card[side].url" :alt="side === 'front' ? $tx('Front') : $tx('Back')" /><input type="file" accept="image/jpeg,image/png" :disabled="busy" :data-testid="`agent-order-photo-${side}-${index}`" @change="selectPhoto(card, side, $event)" /><small v-if="card[side].id" class="uploaded">{{ $tx('Uploaded') }}</small></label></div>
            </article>
          </section>
        </fieldset>
      </form>
      <p v-else-if="detailLoading" role="status">{{ $tx('Loading order…') }}</p>
      <template v-else-if="detail">
        <div class="order-detail-heading"><el-button :disabled="busy" @click="show(detail.order.id)">{{ $tx('Refresh') }}</el-button><el-tag>{{ orderStatus(detail.order) }}</el-tag><el-button v-if="!detailOnly && detail.order.batchNo" type="primary" plain :disabled="busy" @click="viewSubmit">{{ $tx('View Submit and review') }}</el-button><el-button v-if="!detailOnly && route.meta.adminUnified && detail.order.gradingOrderId" v-hasPermi="['nxr:order:list']" :disabled="busy" @click="openGradingOrder">{{ $t('nav.admissionGrading') }}</el-button><el-button v-if="!detailOnly && detail.order.canSubmit" type="primary" :disabled="busy" @click="selected[detail.order.id] = detail.order; selectForSubmit()">{{ $tx('Add to Submit') }}</el-button></div>
        <el-alert v-if="canCompleteExistingIntake(detail.order)" type="warning" :closable="false"><template #title>{{ $tx('This existing intake still needs receipt or card checks.') }}</template><el-button :disabled="busy" data-testid="agent-complete-existing-intake" @click="legacyIntakeId = detail.order.id">{{ $tx('Complete existing intake') }}</el-button></el-alert><dl class="agent-facts"><div><dt>{{ $tx('Customer') }}</dt><dd>{{ detail.order.clientName }}</dd></div><div><dt>{{ $tx('Phone number') }}</dt><dd>{{ detail.order.phone || '—' }}</dd></div><div><dt>{{ $tx('Email') }}</dt><dd>{{ detail.order.email || '—' }}</dd></div><div data-testid="agent-order-customer-address-detail"><dt>{{ $tx('Customer address') }}</dt><dd>{{ addressLabel(detail.order.customerAddress) || $tx('Not provided') }}</dd></div><div><dt>{{ $tx('Cards') }}</dt><dd>{{ detail.order.cardCount }}</dd></div><div v-if="detail.order.notes"><dt>{{ $tx('Notes') }}</dt><dd>{{ detail.order.notes }}</dd></div></dl>
        <div v-if="detail.order.batchNo && detail.order.gradingOrderNo" class="order-tracking"><el-button :disabled="busy" @click="createTrackingLink">{{ trackingLink ? $tx('Replace link') : $tx('Create customer tracking link') }}</el-button><el-input v-if="trackingLink" :model-value="trackingLink" readonly :aria-label="$tx('Private customer tracking link')" /><el-button v-if="trackingLink" :disabled="busy" @click="copyLink">{{ $tx('Copy') }}</el-button></div>
        <h3>{{ $tx('Order cards') }}</h3><el-table :data="detail.cards" border data-testid="agent-order-cards"><el-table-column prop="cardName" :label="$tx('Card name')" min-width="170" /><el-table-column prop="brandName" :label="$tx('Brand')" min-width="120" /><el-table-column prop="setName" :label="$tx('Card set')" min-width="150" /><el-table-column prop="officialCardNumber" :label="$tx('Official card number')" min-width="140" /><el-table-column prop="languageCode" :label="$tx('Language')" min-width="90" /><el-table-column :label="$tx('Status')" min-width="150"><template #default="{row}">{{ row.fulfillmentStatusCode === 'received' ? $tx('Awaiting grading') : agentFulfillmentStatusLabel(displayedCardStatus(detail.order.statusCode, row.fulfillmentStatusCode || row.statusCode)) }}</template></el-table-column><el-table-column prop="gradingCertId" :label="$tx('Certificate ID')" min-width="140" /><el-table-column :label="$tx('Images')" width="90"><template #default="{row}"><el-button link type="primary" @click="photoCard = row.id">{{ $tx('View') }}</el-button></template></el-table-column></el-table>
        <AgentPartnerReturns v-if="detail.order.gradingOrderId" :key="detail.order.gradingOrderId" :order-id="detail.order.gradingOrderId" />
        <section v-if="detail.order.gradingOrderNo" class="order-progress" data-testid="agent-order-fulfillment-progress"><h3>{{ $tx('Grading and shipping progress') }}</h3><p v-if="timelineLoading" role="status">{{ $tx('Loading history…') }}</p><el-alert v-if="timelineError" :title="localizeBackendMessage(timelineError)" type="error" :closable="false" /><el-button v-if="timelineError" @click="loadFulfillment(detail.order.gradingOrderNo)">{{ $tx('Retry') }}</el-button><ol v-if="fulfillmentOrder?.timeline.length" class="agent-timeline"><li v-for="event in fulfillmentOrder.timeline" :key="event.id"><div><strong>{{ $tx(event.title) }}</strong><time>{{ agentDateLabel(event.createdAt) }}</time></div><p v-if="event.detail">{{ localizeOrderEventDetail(event) }}</p></li></ol><p v-else-if="!timelineLoading && !timelineError" class="muted-copy">{{ $tx('No records yet.') }}</p></section>
        <AgentIntakesPanel v-if="legacyIntakeId" :key="legacyIntakeId" :initial-record-id="legacyIntakeId" legacy-only @closed="closeLegacyIntake" />
        <AgentTimeline :events="detail.events" :scope="{ intakeId: detail.order.id }" />
        <el-drawer :model-value="Boolean(photoCard)" :title="$tx('Card photos')" size="min(620px, 94vw)" :append-to-body="false" @close="photoCard = null"><div v-for="card in detail.cards.filter(item => item.id === photoCard)" :key="card.id"><h3>{{ card.cardName }}</h3><AgentPrivatePhoto :photo-id="card.frontPhotoId" :label="$tx('Front')" /><AgentPrivatePhoto :photo-id="card.backPhotoId" :label="$tx('Back')" /></div></el-drawer>
      </template>
      <template #footer><div class="order-drawer-footer"><span v-if="photoProgress" role="status">{{ photoProgress }}</span><el-button :disabled="busy" @click="close()">{{ $tx('Close') }}</el-button><el-button v-if="mode === 'new'" type="primary" :loading="busy" :disabled="!options || optionsLoading" data-testid="agent-create-order" native-type="submit" form="agent-new-order-form">{{ $tx('Create Order') }}</el-button></div></template>
    </el-drawer>

  </section>
</template>
<style scoped>
.order-panel .agent-results-table{min-width:1280px}.order-panel .agent-results-table th:nth-child(7),.order-panel .agent-results-table td:nth-child(7){min-width:190px}.order-panel .agent-results-table th:nth-child(2),.order-panel .agent-results-table td:nth-child(2){white-space:nowrap;min-width:105px}.order-panel .agent-results-table td:nth-child(3){min-width:190px}
.order-toolbar-actions,.order-selection,.order-detail-heading,.order-tracking,.order-drawer-footer{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.order-total{color:var(--el-text-color-secondary);font-size:14px}.order-selection{padding:14px 18px;border:1px solid var(--el-color-primary-light-7);background:var(--el-color-primary-light-9);border-radius:6px;margin-bottom:18px}.order-form-section{padding:20px;border:1px solid var(--el-border-color-light);border-radius:8px}.order-form-section h3{margin:0 0 18px}.order-form-section .agent-toolbar h3{margin:0}.customer-address-inline{margin-top:16px}.order-card-editor{padding:20px;border:1px solid var(--el-border-color-light);border-radius:8px;margin-top:16px;background:var(--el-fill-color-lighter)}.order-card-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}.order-photo-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;margin-top:20px}.order-photo-upload{padding:16px;border:1px dashed var(--el-border-color);border-radius:6px;background:var(--el-bg-color)}.order-photo-upload img{width:100%;height:140px;object-fit:contain}.required{display:inline-block;margin-left:4px;color:#ff4d4f;font-size:18px;font-weight:700;line-height:1;vertical-align:middle}.order-required-hint{margin:0;color:var(--el-text-color-secondary);font-size:13px}.uploaded{color:var(--el-color-success)}.order-tracking{margin:20px 0}.order-tracking .el-input{flex:1;min-width:180px}.order-drawer-footer{justify-content:flex-end}.order-drawer-footer span{margin-right:auto;font-size:13px;color:var(--el-text-color-secondary)}
@media(max-width:850px){.order-card-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.order-form-section,.order-card-editor{padding:14px}}
@media(max-width:600px){.order-card-grid,.order-photo-grid{grid-template-columns:1fr}.order-toolbar-actions{gap:6px}.order-toolbar-actions .el-button{margin-left:0}.order-drawer-footer{align-items:stretch}.order-drawer-footer span{width:100%}}
</style>
