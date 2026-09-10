<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { addBatchInbound, fetchMerchantBatch, fetchMerchantBatches, localTrackingUrl, rotateTrackingLink, revokeTrackingLink, type MerchantBatch } from '../lib/merchantBatches'
import { orderStatusLabel, orderDisplayStatus } from '../lib/orderProgress'
const props = defineProps<{ refreshKey?: string }>()
const batches = ref<MerchantBatch[]>([]), selected = ref<MerchantBatch | null>(null), loading = ref(false), busy = ref(false), error = ref(''), links = ref<Record<string, string>>({})
const carrier = ref(''), trackingNumber = ref(''), notice = ref('')
const canAddInbound = computed(() => Boolean(selected.value?.orders?.length && selected.value.orders.every(order => ['awaiting_inbound', 'inbound_shipped'].includes(order.statusCode))))
async function load() {
  loading.value = true; error.value = ''
  try { batches.value = (await fetchMerchantBatches()).items }
  catch (e) { error.value = e instanceof Error ? e.message : 'Unable to load batches.' }
  finally { loading.value = false }
}
async function select(batchNo: string) {
  busy.value = true; error.value = ''; selected.value = null; notice.value = ''
  try { selected.value = await fetchMerchantBatch(batchNo) }
  catch (e) { error.value = e instanceof Error ? e.message : 'Unable to load this batch.' }
  finally { busy.value = false }
}
async function rotate(orderNo: string) {
  if (!selected.value) return
  busy.value = true; error.value = ''
  try { const result = await rotateTrackingLink(selected.value.batchNo, orderNo); links.value[orderNo] = localTrackingUrl(result.trackingToken); notice.value = 'A new link is ready. The previous link is no longer valid.' }
  catch (e) { error.value = e instanceof Error ? e.message : 'Unable to create a new tracking link.' }
  finally { busy.value = false }
}
async function revoke(orderNo: string) {
  if (!selected.value) return
  busy.value = true; error.value = ''
  try { await revokeTrackingLink(selected.value.batchNo, orderNo); delete links.value[orderNo]; notice.value = 'The tracking link has been revoked. Create a new link when needed.' }
  catch (e) { error.value = e instanceof Error ? e.message : 'Unable to revoke this link.' }
  finally { busy.value = false }
}
async function saveInbound() {
  if (!selected.value) return
  busy.value = true; error.value = ''
  try { await addBatchInbound(selected.value.batchNo, carrier.value, trackingNumber.value); selected.value = await fetchMerchantBatch(selected.value.batchNo); carrier.value = ''; trackingNumber.value = '' }
  catch (e) { error.value = e instanceof Error ? e.message : 'Unable to save the parcel.' }
  finally { busy.value = false }
}
onMounted(load)
watch(() => props.refreshKey, () => { void load() })
</script>
<template><section class="form-section batch-panel"><div class="form-row"><h2>Your batches and customer orders</h2><button type="button" class="btn-secondary" :disabled="loading" @click="load">Refresh batches</button></div><p class="muted-copy">Keep each customer’s cards in a separate labeled inner package. Return delivery goes to your agent address as one batch.</p><p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="notice" class="muted-copy" role="status">{{ notice }}</p><p v-if="loading">Loading batches…</p><div v-else class="batch-list"><button v-for="batch in batches" :key="batch.batchNo" type="button" class="batch-choice" :class="{ active: selected?.batchNo === batch.batchNo }" :disabled="busy" @click="select(batch.batchNo)"><strong>{{ batch.batchName || batch.batchNo }}</strong><small>{{ batch.batchNo }} · {{ orderStatusLabel(batch.statusCode) }}</small></button><p v-if="!batches.length" class="muted-copy">No batches yet. Import the first one above.</p></div><template v-if="selected"><h3>{{ selected.batchNo }} · Customer orders</h3><div class="table-scroll"><table class="portal-table"><thead><tr><th>Customer reference</th><th>Order</th><th>Cards</th><th>Status</th><th>Private tracking</th></tr></thead><tbody><tr v-for="order in selected.orders" :key="order.orderNo"><td>{{ order.clientReference }}<small>{{ order.clientDisplayName }}</small></td><td><router-link :to="`/account/orders/${order.orderNo}`">{{ order.orderNo }}</router-link></td><td>{{ order.totalCardCount ?? order.cardCount }}</td><td>{{ orderStatusLabel(orderDisplayStatus(order.statusCode, order.admissionStatus)) }}</td><td><button type="button" class="text-button" :disabled="busy" @click="rotate(order.orderNo)">Create new link</button> <button type="button" class="text-button" :disabled="busy" @click="revoke(order.orderNo)">Revoke link</button><input v-if="links[order.orderNo]" :value="links[order.orderNo]" readonly aria-label="Private tracking link" @focus="($event.target as HTMLInputElement).select()" /></td></tr></tbody></table></div><p class="muted-copy">Creating a new link revokes the previous link. Share it only with that customer; it provides read-only progress without agent pricing.</p><div v-for="parcel in selected.shipments" :key="`${parcel.directionCode}:${parcel.trackingNumber}`" class="detail-row"><strong>{{ parcel.directionCode === 'inbound' ? 'Sent to NXR' : 'Returning to agent' }}</strong><span>{{ parcel.carrierName }} · {{ parcel.trackingNumber }}</span></div><form v-if="canAddInbound" class="portal-form" @submit.prevent="saveInbound"><h3>Send this batch to NXR</h3><p class="muted-copy">After all child orders are paid, record the main parcel tracking number.</p><div class="form-grid"><label>Carrier<input v-model="carrier" required maxlength="128" /></label><label>Tracking number<input v-model="trackingNumber" required maxlength="128" /></label></div><button class="btn-secondary" :disabled="busy">Save inbound parcel</button></form></template></section></template>
<style scoped>.batch-panel{margin-top:24px}.batch-list{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:10px;margin:16px 0}.batch-choice{display:flex;flex-direction:column;gap:7px;align-items:flex-start;text-align:left;border:1px solid var(--border,#ddd);border-radius:10px;background:transparent;color:inherit;padding:14px;cursor:pointer}.batch-choice.active{border-color:var(--gold,#ad8c52)}td small{display:block;color:var(--text-muted,#777)}td input{max-width:220px;padding:6px;font-size:11px}.batch-panel form{margin-top:28px}</style>
