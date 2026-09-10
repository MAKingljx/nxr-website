<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { GradingOrder } from '../lib/customer'
import { acceptOrderTerms, fetchAdmission, resubmitApplication, uploadOrderPhoto, removeUnusedOrderPhoto, type Admission } from '../lib/orderApplication'
import PrivateOrderPhoto from './PrivateOrderPhoto.vue'
import { formatMoney, orderStatusLabel } from '../lib/orderProgress'
const props = defineProps<{ order: GradingOrder; refreshTick?: string }>()
const emit = defineEmits<{ change: [Admission | null]; refresh: [] }>()
const admission = ref<Admission | null>(null), loading = ref(true), busy = ref(false), error = ref(''), accepted = ref(false), note = ref('')
const supplementalPhotos = ref<number[]>([])
let generation = 0
const showConfirmation = computed(() => Boolean(admission.value?.canAcceptTerms))
async function load() {
  const current = ++generation
  loading.value = true
  try {
    const next = await fetchAdmission(props.order.orderNo)
    if (current !== generation) return
    if (admission.value?.termsVersion !== next.termsVersion || admission.value?.admissionRevision !== next.admissionRevision || admission.value?.quoteAmount !== next.quoteAmount) accepted.value = false
    admission.value = next; emit('change', next); error.value = ''
  } catch (e) {
    if (current !== generation) return
    admission.value = null; emit('change', null); error.value = e instanceof Error ? e.message : 'Unable to load application status.'
  } finally { if (current === generation) loading.value = false }
}
async function confirm() {
  if (!admission.value || !accepted.value) return
  busy.value = true; error.value = ''
  try {
    admission.value = await acceptOrderTerms(props.order.orderNo, {
      termsVersion: admission.value.termsVersion, acceptedQuotedAmount: Number(props.order.totalAmount), acceptedCurrency: props.order.currencyCode,
    })
    emit('change', admission.value); emit('refresh')
  } catch (e) { error.value = e instanceof Error ? e.message : 'Unable to confirm the order.' }
  finally { busy.value = false }
}
async function resubmit() {
  busy.value = true; error.value = ''
  try {
    await resubmitApplication(props.order.orderNo, note.value, supplementalPhotos.value)
    note.value = ''; supplementalPhotos.value = []; await load(); emit('refresh')
  } catch (e) { error.value = e instanceof Error ? e.message : 'Unable to resubmit the application.' }
  finally { busy.value = false }
}
async function uploadSupplement(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  if (files.length + supplementalPhotos.value.length > 40) { error.value = 'Upload at most 40 supplemental images at a time.'; input.value = ''; return }
  busy.value = true; error.value = ''
  try { for (const file of files) supplementalPhotos.value.push((await uploadOrderPhoto(file)).id) }
  catch (e) { error.value = e instanceof Error ? e.message : 'An image upload failed. Successfully uploaded images have been kept.' }
  finally { busy.value = false; input.value = '' }
}
async function removeSupplement(id: number) {
  try { await removeUnusedOrderPhoto(id); supplementalPhotos.value = supplementalPhotos.value.filter(value => value !== id) }
  catch (e) { error.value = e instanceof Error ? e.message : 'Unable to remove image.' }
}
watch(() => props.order.orderNo, () => { admission.value = null; accepted.value = false; emit('change', null); void load() }, { immediate: true })
watch(() => props.refreshTick, () => { void load() })
</script>
<template>
  <section v-if="!admission?.legacyOrder || error" class="form-section admission-panel no-print">
    <div class="form-row"><h2>Application review</h2><span v-if="admission" class="status-pill">{{ orderStatusLabel(admission.admissionStatus) }}</span></div>
    <p v-if="error" class="form-error" role="alert">{{ error }} <button type="button" class="text-button" @click="load">Retry</button></p>
    <p v-if="loading && !admission" class="muted-copy">Loading application status…</p>
    <template v-if="admission">
      <p v-if="admission.admissionStatus === 'pending_review'">Your card details are under review. Payment will open after NXR accepts the application.</p>
      <p v-else-if="admission.admissionStatus === 'needs_information'">NXR needs more information before accepting this application.</p>
      <p v-else-if="admission.admissionStatus === 'rejected'">NXR could not accept this application. No payment is requested.</p>
      <p v-if="admission.decisionNote" class="admission-note">{{ admission.decisionNote }}</p>
      <p v-if="admission.paymentExpired" class="form-error">The payment deadline has passed. Contact NXR to request a new review before paying.</p>
      <p v-else-if="admission.paymentDueAt && ['terms_confirmation', 'awaiting_payment', 'payment_review'].includes(order.statusCode)">Payment due by <strong>{{ new Date(admission.paymentDueAtIso || admission.paymentDueAt).toLocaleString() }}</strong>.</p>
      <form v-if="admission.canResubmit" class="portal-form" @submit.prevent="resubmit"><label>Additional information<textarea v-model="note" maxlength="2000" rows="4" /></label><label>Additional photos (optional)<input type="file" accept="image/jpeg,image/png" multiple :disabled="busy" @change="uploadSupplement" /></label><div class="supplement-images"><div v-for="id in supplementalPhotos" :key="id"><PrivateOrderPhoto :photo-id="id" label="Additional photo" /><button type="button" class="text-button" :disabled="busy" @click="removeSupplement(id)">Remove</button></div></div><button type="submit" class="btn-primary" :disabled="busy || (!note.trim() && !supplementalPhotos.length)">{{ busy ? 'Please wait…' : 'Resubmit for review' }}</button></form>
      <details v-if="admission.events?.length"><summary>Review history and additional information</summary><article v-for="event in admission.events" :key="event.id" class="admission-history"><strong>{{ event.title }}</strong><time>{{ new Date(event.createdAt).toLocaleString() }}</time><p v-if="event.detail">{{ event.detail }}</p></article><PrivateOrderPhoto v-for="id in admission.supplementalPhotoIds" :key="id" :photo-id="id" label="Submitted additional photo" /></details>
      <div v-if="showConfirmation" class="confirm-order">
        <h3>Confirm your order before payment</h3>
        <p>{{ order.totalCardCount }} cards · Grading {{ formatMoney(order.serviceFee, order.currencyCode) }} · Return shipping {{ formatMoney(order.returnShippingFee, order.currencyCode) }}</p>
        <p><strong>Total: {{ formatMoney(order.totalAmount, order.currencyCode) }}</strong></p>
        <div><strong>Return delivery</strong><p>{{ order.contactName }} · {{ order.contactPhone }}</p><p>{{ [order.returnAddressLine1, order.returnAddressLine2, order.returnCity, order.returnRegion, order.returnPostalCode, order.returnCountry].filter(Boolean).join(', ') }}</p><p>{{ order.returnShippingOptionName }}</p></div>
        <p v-if="admission.turnaroundText">{{ admission.turnaroundText }}</p>
        <div class="terms-text" tabindex="0">{{ admission.termsText }}</div>
        <label class="check-label"><input v-model="accepted" type="checkbox" /> I have checked the card list, price and return address, and accept terms {{ admission.termsVersion }}.</label>
        <button type="button" class="btn-primary" :disabled="busy || !accepted" @click="confirm">Confirm and show payment methods</button>
      </div>
      <p v-else-if="admission.termsAcceptedAt" class="muted-copy">Order details and terms {{ admission.termsVersion }} confirmed.</p>
    </template>
  </section>
</template>
<style scoped>.admission-note{white-space:pre-wrap;border-left:3px solid var(--gold,#ad8c52);padding:8px 14px}.confirm-order{display:grid;gap:14px}.terms-text{white-space:pre-wrap;max-height:260px;overflow:auto;border:1px solid var(--border,#ddd);border-radius:8px;padding:16px;line-height:1.7}.check-label{display:flex;align-items:flex-start;gap:10px}.check-label input{width:auto;margin-top:4px}</style>
