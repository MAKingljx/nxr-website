<script setup lang="ts">
import { computed, onBeforeMount, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import PrivateTrackingPhoto from '../components/PrivateTrackingPhoto.vue'
import { fetchPrivateOrderTracking, verifyPrivateOrderTracking, type PrivateOrderTracking } from '../lib/merchantBatches'
import { orderDisplayStatus } from '../lib/orderProgress'
import { privateOrderNumber, privateReturnSummary, privateTrackingStatus, privateReturnStatus, privateTimelineTitle, privateTrackingDate } from '../lib/privateOrderTracking'
const props = defineProps<{ token: string }>()
const { t, te, locale } = useI18n()
const order = ref<PrivateOrderTracking | null>(null), error = ref(false), loading = ref(false), updatedAt = ref<Date | null>(null)
const contact = ref(''), accessToken = ref(''), verifying = ref(false)
const returnSummary = computed(() => order.value ? privateReturnSummary(order.value) : null)
const displayOrderNo = computed(() => order.value ? privateOrderNumber(order.value) : '')
const displayStatus = computed(() => order.value && returnSummary.value
  ? privateTrackingStatus(orderDisplayStatus(order.value.statusCode, order.value.admissionStatus), returnSummary.value) : '')
function statusLabel(code: string) {
  return te(`tracking.statuses.${code}`) ? t(`tracking.statuses.${code}`) : code || '—'
}
function parcelStatusLabel(code: string) {
  return statusLabel(privateReturnStatus(code))
}
function dateLabel(value: string | Date | null | undefined) {
  return privateTrackingDate(value, locale.value)
}
const timelineTitle = (event: PrivateOrderTracking['timeline'][number]) => privateTimelineTitle(event, (key, parameters = {}) => t(key, parameters))
let generation = 0, timer: ReturnType<typeof setInterval> | undefined, sessionTimer: ReturnType<typeof setTimeout> | undefined
let previousReferrer: string | null = null, referrerMeta: HTMLMetaElement | null = null, addedReferrer = false
onBeforeMount(() => {
  referrerMeta = document.querySelector('meta[name="referrer"]')
  if (referrerMeta) previousReferrer = referrerMeta.getAttribute('content')
  else { referrerMeta = document.createElement('meta'); referrerMeta.name = 'referrer'; document.head.appendChild(referrerMeta); addedReferrer = true }
  referrerMeta.content = 'no-referrer'
})
async function refresh() {
  if (!accessToken.value) return
  const current = ++generation
  loading.value = true; error.value = false
  try { const next = await fetchPrivateOrderTracking(props.token, accessToken.value); if (generation !== current) return; order.value = next; updatedAt.value = new Date() }
  catch { if (generation === current) { order.value = null; accessToken.value = ''; if (sessionTimer) clearTimeout(sessionTimer); error.value = true } }
  finally { if (generation === current) loading.value = false }
}
async function verify() {
  if (verifying.value || !contact.value.trim()) return
  const current = ++generation, submittedContact = contact.value.trim(), token = props.token
  verifying.value = true; error.value = false; order.value = null; accessToken.value = ''
  if (sessionTimer) clearTimeout(sessionTimer)
  try {
    const result = await verifyPrivateOrderTracking(token, submittedContact)
    if (current !== generation) return
    if (!result.accessToken || !Number.isFinite(result.expiresInSeconds) || result.expiresInSeconds <= 0) throw new Error('Invalid verification response')
    contact.value = ''; accessToken.value = result.accessToken; verifying.value = false
    sessionTimer = setTimeout(() => { generation++; order.value = null; accessToken.value = ''; error.value = true; loading.value = false }, result.expiresInSeconds * 1000)
    await refresh()
  } catch { if (current === generation) { contact.value = ''; accessToken.value = ''; order.value = null; error.value = true } }
  finally { if (current === generation) verifying.value = false }
}
watch(() => props.token, () => { generation++; if (sessionTimer) clearTimeout(sessionTimer); contact.value = ''; accessToken.value = ''; order.value = null; loading.value = false; verifying.value = false; error.value = false }, { immediate: true })
onMounted(() => { timer = setInterval(() => { if (!document.hidden && !loading.value && order.value) void refresh() }, 30_000) })
onBeforeUnmount(() => { generation++; if (timer) clearInterval(timer); if (sessionTimer) clearTimeout(sessionTimer); contact.value = ''; accessToken.value = ''; order.value = null; if (addedReferrer) referrerMeta?.remove(); else if (referrerMeta && previousReferrer !== null) referrerMeta.content = previousReferrer; else referrerMeta?.removeAttribute('content') })
</script>
<template>
  <LegacySiteNav v-if="accessToken" />
  <main class="portal-page private-tracking">
    <div class="portal-heading">
      <div><p class="section-tag">{{ t('tracking.eyebrow') }}</p><h1>{{ t('tracking.title') }}</h1><p>{{ t('tracking.contactAgent') }}</p></div>
      <button v-if="accessToken" class="btn-secondary" :disabled="loading" @click="refresh">{{ t(loading ? 'tracking.refreshing' : 'tracking.refresh') }}</button>
    </div>
    <p v-if="error" class="form-error" role="alert">{{ t('tracking.verifyFailed') }}</p>
    <form v-if="!accessToken" class="form-section tracking-verify" data-testid="private-tracking-verify" @submit.prevent="verify">
      <h2>{{ t('tracking.verifyTitle') }}</h2>
      <label for="tracking-contact">{{ t('tracking.verifyLabel') }}</label>
      <input id="tracking-contact" v-model="contact" type="text" autocomplete="off" autocapitalize="off" spellcheck="false" maxlength="320" required :disabled="verifying" data-testid="private-tracking-contact" />
      <button type="submit" class="btn-primary" :disabled="verifying || !contact.trim()" data-testid="private-tracking-verify-submit">{{ t(verifying ? 'tracking.verifying' : 'tracking.verifySubmit') }}</button>
    </form>
    <template v-if="order">
      <section class="form-section">
        <h2>{{ displayOrderNo }}</h2>
        <p><span v-if="order.clientReference && order.clientReference !== displayOrderNo">{{ order.clientReference }} · </span>{{ t('tracking.cardCount', { count: order.totalCardCount ?? order.cardCount ?? 0 }) }}</p>
        <strong class="status-pill">{{ statusLabel(displayStatus) }}</strong>
        <p class="muted-copy">{{ t('tracking.updated', { time: dateLabel(updatedAt) }) }}</p>
      </section>

      <section v-if="order.cards?.length" class="form-section" data-testid="private-order-cards">
        <h2>{{ t('tracking.orderCards') }}</h2>
        <article v-for="card in order.cards" :key="card.id" class="order-card">
          <div class="order-card-heading"><h3>{{ card.cardName }}</h3><span class="status-pill">{{ parcelStatusLabel(card.statusCode) }}</span></div>
          <dl class="order-card-facts"><div v-if="card.brandName"><dt>{{ t('tracking.brand') }}</dt><dd>{{ card.brandName }}</dd></div><div v-if="card.setName"><dt>{{ t('tracking.cardSet') }}</dt><dd>{{ card.setName }}</dd></div><div v-if="card.cardNumber"><dt>{{ t('tracking.cardNumber') }}</dt><dd>{{ card.cardNumber }}</dd></div><div v-if="card.languageCode"><dt>{{ t('tracking.language') }}</dt><dd>{{ card.languageCode }}</dd></div><div v-if="card.certificateId"><dt>{{ t('tracking.certificateId') }}</dt><dd>{{ card.certificateId }}</dd></div></dl>
          <div class="order-card-photos"><PrivateTrackingPhoto :token="token" :access-token="accessToken" :card-id="card.id" side="front" :available="card.hasFrontPhoto" /><PrivateTrackingPhoto :token="token" :access-token="accessToken" :card-id="card.id" side="back" :available="card.hasBackPhoto" /></div>
        </article>
      </section>

      <section v-if="returnSummary?.hasParcels" class="form-section" data-testid="private-return-parcels">
        <h2>{{ t('tracking.returnToAgent') }}</h2>
        <p class="muted-copy">{{ t('tracking.returnEndpoint') }}</p>
        <div class="return-summary" aria-live="polite">
          <strong>{{ t('tracking.deliveredCount', { count: returnSummary.delivered, total: returnSummary.total }) }}</strong>
          <span>{{ t('tracking.inTransitCount', { count: returnSummary.inTransit }) }}</span>
          <span>{{ t('tracking.remainingCount', { count: returnSummary.notReturned }) }}</span>
        </div>
        <progress v-if="returnSummary.total > 0" class="return-progress" :value="returnSummary.delivered" :max="returnSummary.total" :aria-label="t('tracking.deliveredCount', { count: returnSummary.delivered, total: returnSummary.total })" />
        <p v-if="returnSummary.complete" class="return-complete" data-testid="private-return-complete">{{ t('tracking.allDelivered') }}</p>
        <article v-for="parcel in order.returnParcels" :key="parcel.parcelNo" class="return-parcel">
          <div class="return-parcel-heading"><h3>{{ parcel.parcelNo }}</h3><span class="status-pill">{{ parcelStatusLabel(parcel.statusCode) }}</span></div>
          <dl class="return-parcel-facts">
            <div><dt>{{ t('tracking.carrier') }}</dt><dd>{{ parcel.carrierName || '—' }}</dd></div>
            <div><dt>{{ t('tracking.trackingNumber') }}</dt><dd><code>{{ parcel.trackingNumber || '—' }}</code></dd></div>
            <div><dt>{{ t('tracking.shippedAt') }}</dt><dd>{{ dateLabel(parcel.shippedAt) }}</dd></div>
            <div><dt>{{ t('tracking.deliveredAt') }}</dt><dd>{{ dateLabel(parcel.deliveredAt) }}</dd></div>
          </dl>
          <div class="return-card-table">
            <table><caption>{{ t('tracking.orderCards') }}</caption><thead><tr><th>{{ t('tracking.cardName') }}</th><th>{{ t('tracking.certificateId') }}</th><th>{{ t('common.status') }}</th></tr></thead>
              <tbody><tr v-for="(card, index) in parcel.cards" :key="`${card.certId || 'card'}-${index}`"><td>{{ card.cardName }}</td><td><code>{{ card.certId || '—' }}</code></td><td>{{ parcelStatusLabel(card.statusCode) }}</td></tr></tbody>
            </table>
          </div>
        </article>
      </section>

      <section class="form-section"><h2>{{ t('tracking.history') }}</h2><ol class="tracking-timeline"><li v-for="(event, index) in order.timeline" :key="index"><strong>{{ timelineTitle(event) }}</strong><time>{{ dateLabel(event.createdAt) }}</time></li><li v-if="!order.timeline?.length">{{ t('tracking.noHistory') }}</li></ol></section>
      <section v-if="order.shipments?.length" class="form-section"><h2>{{ t('tracking.parcelProgress') }}</h2><div v-for="(parcel, index) in order.shipments" :key="index" class="detail-row"><div><strong>{{ t(parcel.directionCode === 'inbound' ? 'tracking.agentToNxr' : 'tracking.nxrToAgent') }}</strong><span>{{ parcel.carrierName }} · {{ parcel.trackingNumber }}</span></div><span>{{ parcel.directionCode === 'outbound' ? parcelStatusLabel(parcel.statusCode) : statusLabel(parcel.statusCode) }}</span></div></section>
    </template>
    <p v-else-if="loading">{{ t('tracking.loading') }}</p>
  </main>
  <LegacySiteFooter v-if="accessToken" />
</template>

<style scoped>
.private-tracking{width:100%;max-width:none;box-sizing:border-box;min-width:0}.private-tracking>section,.private-tracking .portal-heading>div{min-width:0}.private-tracking h2,.private-tracking h3{overflow-wrap:anywhere}
.tracking-verify{display:grid;gap:12px;max-width:520px}.tracking-verify h2{margin:0}.tracking-verify input{width:100%;min-width:0}.tracking-verify button{justify-self:start}
.tracking-timeline{list-style:none;padding:0}.tracking-timeline li{display:flex;justify-content:space-between;gap:20px;padding:18px 0;border-bottom:1px solid var(--border,#ddd)}.tracking-timeline strong{min-width:0;overflow-wrap:anywhere}.tracking-timeline time{flex-shrink:0;font-size:13px;color:var(--text-muted,#777)}
.return-summary{display:flex;align-items:center;flex-wrap:wrap;gap:12px 24px;margin:20px 0 12px;font-size:14px}.return-summary span{color:var(--text-muted,#777)}.return-progress{display:block;width:100%;height:8px;margin-bottom:20px;border:0;border-radius:6px;overflow:hidden;accent-color:#377b66}.return-progress::-webkit-progress-bar{background:#e7eeeb}.return-progress::-webkit-progress-value{background:#377b66}.return-progress::-moz-progress-bar{background:#377b66}.return-complete{color:#377b66;font-weight:600}
.return-parcel{min-width:0;margin-top:20px;padding:22px;border:1px solid var(--border,#dfe5e2);border-radius:10px}.return-parcel-heading{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px}.return-parcel-heading h3{margin:0;font-size:17px}.return-parcel-facts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px 24px;margin:22px 0}.return-parcel-facts div{min-width:0}.return-parcel-facts dt{font-size:12px;color:var(--text-muted,#777);margin-bottom:6px}.return-parcel-facts dd{margin:0;font-size:14px;overflow-wrap:anywhere}
.order-card{margin-top:16px;padding:18px;border:1px solid var(--border,#dfe5e2);border-radius:10px}.order-card-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.order-card-heading h3{margin:0}.order-card-facts{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:12px;margin:16px 0}.order-card-facts dt{font-size:12px;color:var(--text-muted,#777)}.order-card-facts dd{margin:4px 0 0;overflow-wrap:anywhere}.order-card-photos{display:flex;gap:16px;flex-wrap:wrap}
.return-card-table{max-width:100%;overflow-x:auto;overscroll-behavior-x:contain}.return-card-table table{width:100%;border-collapse:collapse;font-size:13px;text-align:left}.return-card-table caption{text-align:left;font-weight:600;padding:8px 0 14px}.return-card-table th,.return-card-table td{padding:12px 10px;border-bottom:1px solid var(--border,#e3e7e5);vertical-align:top}.return-card-table th{font-size:12px;color:var(--text-muted,#777)}.return-card-table td:first-child{min-width:150px;overflow-wrap:anywhere}.return-card-table code{white-space:nowrap}.return-card-table tr:last-child td{border-bottom:0}
@media(max-width:600px){.tracking-timeline li{flex-direction:column;gap:6px}.return-parcel{padding:16px}.return-parcel-facts{grid-template-columns:minmax(0,1fr);gap:14px}.return-summary{align-items:flex-start;flex-direction:column;gap:7px}.return-card-table table{min-width:460px}}
</style>
