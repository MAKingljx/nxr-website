<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import {
  addInboundShipment,
  addSupportTicketMessage,
  createPaymentSession,
  createSupportTicket,
  customerSession,
  fetchCustomerOrder,
  fetchOrderOperations,
  fetchShippingOptions,
  requestShippingChange,
  submitPaymentProof,
  type CustomerOrderOperations,
  type GradingOrder,
  type ShippingOption,
} from '../lib/customer'

const props = defineProps<{ orderNo: string }>()
const router = useRouter()
const order = ref<GradingOrder | null>(null)
const operations = ref<CustomerOrderOperations | null>(null)
const shippingOptions = ref<ShippingOption[]>([])
const paymentSession = ref<Awaited<ReturnType<typeof createPaymentSession>> | null>(null)
const loading = ref(true)
const errorMessage = ref('')
const savingPayment = ref(false)
const savingShipment = ref(false)
const savingTicket = ref(false)
const savingShippingChange = ref(false)
const paymentForm = ref({ provider: 'manual_transfer', payerReference: '', proofReference: '' })
const shipmentForm = ref({ direction: 'inbound', carrierName: '', trackingNumber: '', note: '' })
const ticketForm = ref({ categoryCode: 'inquiry', subject: '', message: '', attachmentReference: '' })
const ticketReplies = ref<Record<number, string>>({})
const shippingChangeForm = ref({ newOptionCode: '', reason: '', attachmentReference: '' })
let refreshTimer: number | undefined

const payment = computed(() => order.value?.payments.find((item) => item.directionCode === 'receivable' && item.paymentTypeCode === 'grading_fee') || null)
const canSubmitPayment = computed(() => ['awaiting_payment', 'payment_review'].includes(order.value?.statusCode || ''))
const canAddInboundShipment = computed(() => ['awaiting_inbound', 'inbound_shipped'].includes(order.value?.statusCode || ''))
const canRequestShippingChange = computed(() => Boolean(order.value && !order.value.shippingLabelCreatedAt && !['return_shipped', 'delivered', 'cancelled'].includes(order.value.statusCode)))
const otherReturnOptions = computed(() => shippingOptions.value.filter((item) => item.optionCode !== operations.value?.effectiveShippingOption.optionCode))

function dateLabel(value: string | null) {
  return value ? new Date(value).toLocaleString() : '-'
}

function money(value: number | null | undefined, currency = order.value?.currencyCode || 'USD') {
  return `${currency} ${Number(value || 0).toFixed(2)}`
}

function statusLabel(value: string | null | undefined) {
  return (value || '-').replaceAll('_', ' ')
}

async function refreshOrder(showLoader = false) {
  if (!customerSession.value) {
    await router.replace(`/account/login?next=/account/orders/${encodeURIComponent(props.orderNo)}`)
    return
  }
  if (showLoader) loading.value = true
  try {
    const [nextOrder, nextOperations] = await Promise.all([
      fetchCustomerOrder(props.orderNo),
      fetchOrderOperations(props.orderNo),
    ])
    order.value = nextOrder
    operations.value = nextOperations
    shippingOptions.value = await fetchShippingOptions(nextOrder.returnCountry)
    if (!shippingChangeForm.value.newOptionCode) {
      shippingChangeForm.value.newOptionCode = shippingOptions.value.find((item) => item.optionCode !== nextOperations.effectiveShippingOption.optionCode)?.optionCode || ''
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to load this order.'
  } finally {
    loading.value = false
  }
}

async function startPaymentSession() {
  if (!order.value) return
  errorMessage.value = ''
  try {
    paymentSession.value = await createPaymentSession(order.value.orderNo, paymentForm.value.provider)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to create the payment session.'
  }
}

async function savePayment() {
  if (!order.value) return
  savingPayment.value = true
  errorMessage.value = ''
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
  errorMessage.value = ''
  try {
    order.value = await addInboundShipment(order.value.orderNo, shipmentForm.value)
    shipmentForm.value = { direction: 'inbound', carrierName: '', trackingNumber: '', note: '' }
    await refreshOrder()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to add tracking.'
  } finally {
    savingShipment.value = false
  }
}

async function saveTicket() {
  if (!order.value) return
  savingTicket.value = true
  errorMessage.value = ''
  try {
    await createSupportTicket(order.value.orderNo, ticketForm.value)
    ticketForm.value = { categoryCode: 'inquiry', subject: '', message: '', attachmentReference: '' }
    await refreshOrder()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to create the support ticket.'
  } finally {
    savingTicket.value = false
  }
}

async function replyToTicket(ticketId: number) {
  if (!order.value || !ticketReplies.value[ticketId]?.trim()) return
  try {
    await addSupportTicketMessage(order.value.orderNo, ticketId, {
      message: ticketReplies.value[ticketId], attachmentReference: '',
    })
    ticketReplies.value[ticketId] = ''
    await refreshOrder()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to send the ticket message.'
  }
}

async function saveShippingChange() {
  if (!order.value) return
  savingShippingChange.value = true
  errorMessage.value = ''
  try {
    await requestShippingChange(order.value.orderNo, shippingChangeForm.value)
    shippingChangeForm.value.reason = ''
    shippingChangeForm.value.attachmentReference = ''
    await refreshOrder()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to request the return-shipping change.'
  } finally {
    savingShippingChange.value = false
  }
}

function printPackingSlip() {
  window.print()
}

watch(() => props.orderNo, async () => {
  errorMessage.value = ''
  paymentSession.value = null
  await refreshOrder(true)
  if (refreshTimer) window.clearInterval(refreshTimer)
  refreshTimer = window.setInterval(() => void refreshOrder(), 20000)
}, { immediate: true })

onBeforeUnmount(() => {
  if (refreshTimer) window.clearInterval(refreshTimer)
})
</script>

<template>
  <LegacySiteNav active="account" cta-href="/submit/order" cta-label="New order" />
  <main class="portal-page">
    <div v-if="loading" class="portal-empty">Loading order...</div>
    <template v-else-if="order && operations">
      <div class="portal-heading">
        <div><p class="section-tag">Grading order</p><h1>{{ order.orderNo }}</h1><p>{{ order.totalCardCount }} card{{ order.totalCardCount === 1 ? '' : 's' }} · {{ order.returnShippingOptionName }} · created {{ dateLabel(order.createdAt) }}</p></div>
        <div class="form-row form-actions"><router-link class="btn-secondary" to="/account/addresses">Addresses</router-link><router-link class="btn-secondary" to="/account/orders">All orders</router-link></div>
      </div>
      <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>

      <section class="order-summary">
        <div><span>Current status</span><strong class="status-pill">{{ statusLabel(order.statusCode) }}</strong></div>
        <div><span>Total</span><strong>{{ money(order.totalAmount) }}</strong></div>
        <div><span>Payment</span><strong>{{ statusLabel(payment?.statusCode) }}</strong></div>
      </section>

      <section class="detail-grid-section billing-section">
        <div class="detail-panel">
          <h2>Frozen quote</h2>
          <div class="invoice-row"><span>Basic grading</span><strong>{{ money(order.serviceFee) }}</strong></div>
          <div class="invoice-row"><span>{{ order.returnShippingOptionName }} (prepaid)</span><strong>{{ money(order.returnShippingFee) }}</strong></div>
          <div class="invoice-row invoice-total"><span>Original total</span><strong>{{ money(order.totalAmount) }}</strong></div>
        </div>
        <div class="detail-panel">
          <h2>Payment and adjustments</h2>
          <p v-if="!order.payments.length" class="muted-copy">No payment record.</p>
          <div v-for="record in order.payments" :key="record.id" class="detail-row"><div><strong>{{ statusLabel(record.paymentTypeCode) }}</strong><span>{{ record.paymentNo || '-' }} · {{ dateLabel(record.createdAt) }}</span></div><div><strong>{{ record.directionCode === 'payable' ? '-' : '' }}{{ money(record.amount, record.currencyCode) }}</strong><span>{{ statusLabel(record.statusCode) }}</span></div></div>
        </div>
      </section>

      <section v-if="canSubmitPayment" id="payment" class="form-section action-section">
        <h2>Payment</h2>
        <p>Choose a channel to create the payment link/QR payload. Manual proof remains available when an automatic provider is not connected.</p>
        <div class="form-row payment-session-actions"><select v-model="paymentForm.provider"><option value="manual_transfer">Manual transfer</option><option value="bank_transfer">Bank transfer</option><option value="wechat_transfer">WeChat transfer</option><option value="alipay_transfer">Alipay transfer</option><option value="stripe">Stripe</option></select><button type="button" class="btn-secondary" @click="startPaymentSession">Create payment session</button></div>
        <div v-if="paymentSession" class="payment-session-panel"><strong>{{ paymentSession.paymentNo }}</strong><span>{{ money(paymentSession.amount, paymentSession.currencyCode) }}</span><a :href="paymentSession.paymentUrl">Open payment link</a><code>{{ paymentSession.qrPayload }}</code></div>
        <form class="portal-form compact-form" @submit.prevent="savePayment">
          <div class="form-grid"><label>Payer reference<input v-model="paymentForm.payerReference" required maxlength="255" placeholder="Name, transfer reference, or last digits" /></label><label>Receipt / proof reference<input v-model="paymentForm.proofReference" maxlength="512" placeholder="Optional receipt URL or internal reference" /></label></div>
          <button class="btn-primary form-submit" :disabled="savingPayment">{{ savingPayment ? 'Submitting...' : 'Submit for confirmation' }}</button>
        </form>
      </section>

      <section v-if="operations.packingSlip" class="form-section packing-slip" id="packing-slip">
        <div class="form-row"><div><h2>Packing slip</h2><p class="muted-copy">Print this and place it inside the parcel.</p></div><button type="button" class="btn-secondary no-print" @click="printPackingSlip">Print packing slip</button></div>
        <div class="packing-code"><span>Intake barcode</span><strong>{{ operations.packingSlip.intakeCode }}</strong><code>{{ operations.packingSlip.qrPayload }}</code></div>
        <div class="invoice-row"><span>Order</span><strong>{{ operations.packingSlip.orderNo }}</strong></div>
        <div class="invoice-row"><span>Slip</span><strong>{{ operations.packingSlip.packingSlipCode }}</strong></div>
        <div class="invoice-row"><span>Total cards</span><strong>{{ operations.packingSlip.totalCardCount }}</strong></div>
        <div class="reference-chips"><span v-for="group in operations.packingSlip.languageGroups" :key="group.languageCode">{{ group.languageCode }} × {{ group.quantity }}</span></div>
        <ol class="packing-instructions"><li v-for="instruction in operations.packingSlip.packingInstructions" :key="instruction">{{ instruction }}</li></ol>
      </section>

      <section v-if="canAddInboundShipment" class="form-section action-section no-print">
        <h2>Register shipment to NXR</h2>
        <p>After posting your parcel, add the carrier and tracking number for the warehouse team.</p>
        <form class="portal-form compact-form" @submit.prevent="saveInboundShipment"><div class="form-grid"><label>Carrier<input v-model="shipmentForm.carrierName" required maxlength="128" /></label><label>Tracking number<input v-model="shipmentForm.trackingNumber" required maxlength="255" /></label></div><label>Shipment note<textarea v-model="shipmentForm.note" maxlength="1000" /></label><button class="btn-primary form-submit" :disabled="savingShipment">{{ savingShipment ? 'Saving...' : 'Add inbound tracking' }}</button></form>
      </section>

      <section v-if="operations.exceptions.length" class="form-section exception-panel">
        <h2>Intake exceptions</h2>
        <article v-for="exception in operations.exceptions" :key="exception.id" class="support-card"><div class="form-row"><strong>{{ exception.title }}</strong><span class="status-pill">{{ statusLabel(exception.statusCode) }}</span></div><p>{{ exception.detail }}</p><p v-if="exception.resolutionNote"><strong>Resolution:</strong> {{ exception.resolutionNote }}</p><time>{{ dateLabel(exception.createdAt) }}</time></article>
      </section>

      <section class="detail-grid-section">
        <div class="detail-panel"><h2>Cards</h2><div v-for="item in order.items" :key="item.id" class="detail-row"><div><strong>{{ item.itemNo }}. {{ item.cardName }}</strong><span>{{ [item.languageCode, item.brandName, item.setName, item.cardNumber].filter(Boolean).join(' · ') }}</span></div><span class="status-pill">{{ statusLabel(item.statusCode) }}</span></div></div>
        <div class="detail-panel"><h2>Shipments</h2><p v-if="!order.shipments.length" class="muted-copy">No shipment recorded yet.</p><div v-for="shipment in order.shipments" :key="shipment.id" class="detail-row"><div><strong>{{ shipment.directionCode }} · {{ shipment.carrierName }}</strong><span>{{ shipment.trackingNumber }} · {{ shipment.shippingOptionName || '' }} · {{ dateLabel(shipment.shippedAt) }}</span></div><span class="status-pill">{{ statusLabel(shipment.statusCode) }}</span></div></div>
      </section>

      <section v-if="operations.trackingEvents.length" class="timeline-panel">
        <h2>Return tracking</h2>
        <ol class="order-timeline"><li v-for="event in operations.trackingEvents" :key="event.id"><span></span><div><strong>{{ event.eventTitle }}</strong><p>{{ [event.locationLabel, event.eventDetail].filter(Boolean).join(' · ') }}</p><time>{{ dateLabel(event.eventTime) }}</time></div></li></ol>
      </section>

      <section v-if="canRequestShippingChange" class="form-section action-section no-print">
        <h2>Request a return-shipping change</h2>
        <p>Changes are allowed only before the return label is created. Any surcharge or refund is recorded as a separate adjustment.</p>
        <form class="portal-form compact-form" @submit.prevent="saveShippingChange"><label>New return option<select v-model="shippingChangeForm.newOptionCode" required><option v-for="option in otherReturnOptions" :key="option.optionCode" :value="option.optionCode">{{ option.displayName }} · {{ money(option.priceAmount, option.currencyCode) }}</option></select></label><label>Reason<textarea v-model="shippingChangeForm.reason" required maxlength="2000" /></label><label>Attachment reference<input v-model="shippingChangeForm.attachmentReference" maxlength="512" /></label><button class="btn-primary form-submit" :disabled="savingShippingChange || !otherReturnOptions.length">{{ savingShippingChange ? 'Submitting...' : 'Submit change request' }}</button></form>
      </section>

      <section v-if="operations.shippingChanges.length" class="form-section">
        <h2>Shipping changes and settlements</h2>
        <article v-for="change in operations.shippingChanges" :key="change.id" class="support-card"><div class="form-row"><strong>{{ change.oldOptionName }} → {{ change.newOptionName }}</strong><span class="status-pill">{{ statusLabel(change.statusCode) }}</span></div><p>Difference: {{ money(change.differenceAmount, change.currencyCode) }} · {{ change.reason }}</p><time>{{ dateLabel(change.createdAt) }}</time></article>
      </section>

      <section class="form-section support-section no-print">
        <h2>Support tickets</h2>
        <article v-for="ticket in operations.tickets" :key="ticket.id" class="support-card"><div class="form-row"><strong>{{ ticket.ticketNo }} · {{ ticket.subject }}</strong><span class="status-pill">{{ statusLabel(ticket.statusCode) }}</span></div><div v-for="message in ticket.messages" :key="message.id" class="ticket-message"><b>{{ message.actorTypeCode }}</b><p>{{ message.message }}</p><time>{{ dateLabel(message.createdAt) }}</time></div><form v-if="ticket.statusCode !== 'closed'" class="portal-form ticket-reply" @submit.prevent="replyToTicket(ticket.id)"><label>Reply<input v-model="ticketReplies[ticket.id]" required maxlength="4000" /></label><button class="btn-secondary" type="submit">Send</button></form></article>
        <form class="portal-form compact-form ticket-create" @submit.prevent="saveTicket"><div class="form-grid"><label>Category<select v-model="ticketForm.categoryCode"><option value="inquiry">General inquiry</option><option value="score_dispute">Score dispute</option><option value="shipping_change">Order / shipping change</option></select></label><label>Subject<input v-model="ticketForm.subject" required maxlength="255" /></label></div><label>Message<textarea v-model="ticketForm.message" required maxlength="4000" /></label><label>Attachment reference<input v-model="ticketForm.attachmentReference" maxlength="512" /></label><button class="btn-primary form-submit" :disabled="savingTicket">{{ savingTicket ? 'Creating...' : 'Create support ticket' }}</button></form>
      </section>

      <section class="timeline-panel"><h2>Live progress</h2><p class="muted-copy">Automatically refreshes every 20 seconds.</p><ol class="order-timeline"><li v-for="event in order.timeline" :key="event.id"><span></span><div><strong>{{ event.title }}</strong><p v-if="event.detail">{{ event.detail }}</p><time>{{ dateLabel(event.createdAt) }}</time></div></li></ol></section>
    </template>
    <p v-else class="form-error">{{ errorMessage || 'Order not found.' }}</p>
  </main>
  <LegacySiteFooter />
</template>
