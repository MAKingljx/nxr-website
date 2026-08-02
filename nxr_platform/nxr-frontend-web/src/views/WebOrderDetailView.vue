<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import { addInboundShipment, customerSession, fetchCustomerOrder, submitPaymentProof, type GradingOrder } from '../lib/customer'

const props = defineProps<{ orderNo: string }>()
const router = useRouter()
const order = ref<GradingOrder | null>(null)
const loading = ref(true)
const errorMessage = ref('')
const savingPayment = ref(false)
const savingShipment = ref(false)
const paymentForm = ref({ provider: 'manual_transfer', payerReference: '', proofReference: '' })
const shipmentForm = ref({ direction: 'inbound', carrierName: '', trackingNumber: '', note: '' })

const payment = computed(() => order.value?.payments.find((item) => item.directionCode === 'receivable') || null)
const canSubmitPayment = computed(() => ['awaiting_payment', 'payment_review'].includes(order.value?.statusCode || ''))
const canAddInboundShipment = computed(() => ['awaiting_inbound', 'inbound_shipped'].includes(order.value?.statusCode || ''))

function dateLabel(value: string | null) {
  return value ? new Date(value).toLocaleString() : '-'
}

async function loadOrder(orderNo: string) {
  if (!customerSession.value) {
    await router.replace(`/account/login?next=/account/orders/${encodeURIComponent(orderNo)}`)
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    order.value = await fetchCustomerOrder(orderNo)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to load this order.'
  } finally {
    loading.value = false
  }
}

async function savePayment() {
  if (!order.value) return
  savingPayment.value = true
  try {
    order.value = await submitPaymentProof(order.value.orderNo, paymentForm.value)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to submit payment proof.'
  } finally {
    savingPayment.value = false
  }
}

async function saveInboundShipment() {
  if (!order.value) return
  savingShipment.value = true
  try {
    order.value = await addInboundShipment(order.value.orderNo, shipmentForm.value)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to add tracking.'
  } finally {
    savingShipment.value = false
  }
}

watch(() => props.orderNo, (orderNo) => void loadOrder(orderNo), { immediate: true })
onMounted(() => undefined)
</script>

<template>
  <LegacySiteNav active="account" cta-href="/submit/order" cta-label="New order" />
  <main class="portal-page">
    <div v-if="loading" class="portal-empty">Loading order...</div>
    <template v-else-if="order">
      <div class="portal-heading"><div><p class="section-tag">Grading order</p><h1>{{ order.orderNo }}</h1><p>{{ order.totalCardCount }} card{{ order.totalCardCount === 1 ? '' : 's' }} · {{ order.serviceLevelCode }} service · created {{ dateLabel(order.createdAt) }}</p></div><router-link class="btn-secondary" to="/account/orders">All orders</router-link></div>
      <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>
      <section class="order-summary"><div><span>Current status</span><strong class="status-pill">{{ order.statusCode.replaceAll('_', ' ') }}</strong></div><div><span>Total</span><strong>{{ order.currencyCode }} {{ Number(order.totalAmount).toFixed(2) }}</strong></div><div><span>Payment</span><strong>{{ payment?.statusCode?.replaceAll('_', ' ') || '-' }}</strong></div></section>
      <section v-if="canSubmitPayment" class="form-section action-section"><h2>Submit payment confirmation</h2><p>Payment integration is prepared for future providers. Until merchant accounts are connected, submit your transfer reference for staff confirmation.</p><form class="portal-form compact-form" @submit.prevent="savePayment"><div class="form-grid"><label>Method<select v-model="paymentForm.provider"><option value="manual_transfer">Manual transfer</option><option value="bank_transfer">Bank transfer</option><option value="wechat_transfer">WeChat transfer</option><option value="alipay_transfer">Alipay transfer</option></select></label><label>Payer reference<input v-model="paymentForm.payerReference" required maxlength="255" placeholder="Name, transfer reference, or last digits" /></label></div><label>Receipt or proof reference<input v-model="paymentForm.proofReference" maxlength="512" placeholder="Optional receipt URL or internal reference" /></label><button class="btn-primary form-submit" :disabled="savingPayment">{{ savingPayment ? 'Submitting...' : 'Submit for confirmation' }}</button></form></section>
      <section v-if="canAddInboundShipment" class="form-section action-section"><h2>Add inbound tracking</h2><p>Once you have sent your cards, add the carrier and tracking number so the receiving team can follow the shipment.</p><form class="portal-form compact-form" @submit.prevent="saveInboundShipment"><div class="form-grid"><label>Carrier<input v-model="shipmentForm.carrierName" required maxlength="128" /></label><label>Tracking number<input v-model="shipmentForm.trackingNumber" required maxlength="255" /></label></div><label>Shipment note<textarea v-model="shipmentForm.note" maxlength="1000" /></label><button class="btn-primary form-submit" :disabled="savingShipment">{{ savingShipment ? 'Saving...' : 'Add inbound tracking' }}</button></form></section>
      <section class="detail-grid-section"><div class="detail-panel"><h2>Cards</h2><div v-for="item in order.items" :key="item.id" class="detail-row"><div><strong>{{ item.itemNo }}. {{ item.cardName }}</strong><span>{{ [item.brandName, item.setName, item.cardNumber].filter(Boolean).join(' · ') }}</span></div><span class="status-pill">{{ item.statusCode.replaceAll('_', ' ') }}</span></div></div><div class="detail-panel"><h2>Shipments</h2><p v-if="!order.shipments.length" class="muted-copy">No shipment recorded yet.</p><div v-for="shipment in order.shipments" :key="shipment.id" class="detail-row"><div><strong>{{ shipment.directionCode }} · {{ shipment.carrierName }}</strong><span>{{ shipment.trackingNumber }} · {{ dateLabel(shipment.shippedAt) }}</span></div><span class="status-pill">{{ shipment.statusCode }}</span></div></div></section>
      <section class="timeline-panel"><h2>Live progress</h2><ol class="order-timeline"><li v-for="event in order.timeline" :key="event.id"><span></span><div><strong>{{ event.title }}</strong><p v-if="event.detail">{{ event.detail }}</p><time>{{ dateLabel(event.createdAt) }}</time></div></li></ol></section>
    </template>
    <p v-else class="form-error">{{ errorMessage || 'Order not found.' }}</p>
  </main>
  <LegacySiteFooter />
</template>
