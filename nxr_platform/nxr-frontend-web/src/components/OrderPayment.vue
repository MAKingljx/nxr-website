<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { customerSession, submitPaymentProof, type GradingOrder } from '../lib/customer'
import { createCheckout, fetchPaymentOptions, capturePaypal, type Checkout, type PaymentOption } from '../lib/payments'
import { fetchOrderCreditQuote, payFromWallet, sameCreditAmount, type OrderCreditQuote } from '../lib/merchant'
import { formatMoney, formatPoints } from '../lib/orderProgress'
import PortalQrCode from './PortalQrCode.vue'
const props = defineProps<{ order: GradingOrder; refreshTick?: string }>()
const emit = defineEmits<{ refresh: [] }>()
const route = useRoute()
const router = useRouter()
const options = ref<PaymentOption[]>([])
const creditQuote = ref<OrderCreditQuote | null>(null)
const checkout = ref<Checkout | null>(null)
const selectedProvider = ref('')
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const paymentDataError = ref('')
const message = ref('')
const capturedReturnKey = ref('')
const captureInFlightKey = ref('')
let paymentDataGeneration = 0
const proof = ref({ provider: 'bank_transfer', payerReference: '', proofReference: '' })
const merchant = computed(() => customerSession.value?.customer.accountTypeCode === 'merchant')
const balance = computed(() => creditQuote.value?.balance || 0)
const validCredit = computed(() => Boolean(creditQuote.value && creditQuote.value.quote.sourceCurrency===props.order.currencyCode && sameCreditAmount(creditQuote.value.quote.sourceAmount,props.order.totalAmount)))
const safePaymentUrl = computed(() => {
  if (!checkout.value?.paymentUrl) return ''
  try { const url = new URL(checkout.value.paymentUrl); return url.protocol === 'https:' && ['www.paypal.com', 'www.sandbox.paypal.com'].includes(url.hostname) ? url.href : '' } catch { return '' }
})
const paypalReturnKey = computed(() => {
  const token = typeof route.query.token === 'string' ? route.query.token : ''
  return token && route.query.payment !== 'cancelled' ? `${props.order.orderNo}:${token}` : ''
})

function attemptKey(provider: string) {
  const key = `nxr:payment:${props.order.orderNo}:${provider}`
  const stored = sessionStorage.getItem(key)
  if (stored) return stored
  const value = crypto.randomUUID()
  sessionStorage.setItem(key, value)
  return value
}
async function refreshPaymentData(showLoader = false) {
  const generation = ++paymentDataGeneration
  if (showLoader) loading.value = true
  paymentDataError.value = ''
  try {
    const [optionResult, quoteResult] = await Promise.allSettled([fetchPaymentOptions(props.order.currencyCode), merchant.value ? fetchOrderCreditQuote(props.order.orderNo) : Promise.resolve(null)])
    if (generation !== paymentDataGeneration) return
    if(optionResult.status==='fulfilled') {
      options.value=optionResult.value
      if (!optionResult.value.some(option => option.provider === selectedProvider.value)) selectedProvider.value=optionResult.value[0]?.provider||''
    } else {options.value=[];paymentDataError.value=optionResult.reason instanceof Error?optionResult.reason.message:'Unable to load payment options.'}
    if(quoteResult.status==='fulfilled') creditQuote.value=quoteResult.value
    else {creditQuote.value=null;paymentDataError.value=quoteResult.reason instanceof Error?quoteResult.reason.message:'Unable to calculate enterprise credits.'}
  } catch (e) {
    if (generation === paymentDataGeneration) {creditQuote.value=null;paymentDataError.value = e instanceof Error ? e.message : 'Unable to load payment options.'}
  } finally {
    if (generation === paymentDataGeneration) loading.value = false
  }
}
async function capturePaypalReturn() {
  const key = paypalReturnKey.value
  if (!key || key === capturedReturnKey.value || key === captureInFlightKey.value) return
  const token = typeof route.query.token === 'string' ? route.query.token : ''
  captureInFlightKey.value = key
  busy.value = true; error.value = ''
  try {
    // Capture is isolated from polling so the same PayPal token is not retried every 20 seconds.
    await capturePaypal(props.order.orderNo, token)
    capturedReturnKey.value = key
    message.value = 'Payment confirmation received.'
    const query = { ...route.query }; delete query.token; delete query.PayerID; delete query.payment
    await router.replace({ query })
    emit('refresh')
  } catch (e) { error.value = e instanceof Error ? e.message : 'Unable to confirm this PayPal payment.' }
  finally { captureInFlightKey.value = ''; busy.value = false }
}
function checkPaymentStatus() {
  void refreshPaymentData()
  emit('refresh')
}
async function pay(provider: string) {
  if(provider==='wallet'&&(!validCredit.value||!creditQuote.value?.sufficient||loading.value))return
  const displayedQuote=creditQuote.value
  busy.value = true; error.value = ''; message.value = ''
  try {
    if (provider === 'wallet') { await payFromWallet(props.order.orderNo, attemptKey(`wallet:${displayedQuote!.quote.settingsVersion}:${displayedQuote!.quote.points}`),displayedQuote!.quote.settingsVersion,String(displayedQuote!.quote.points)); message.value = 'Order paid from your enterprise credits.'; emit('refresh') }
    else { checkout.value = await createCheckout(props.order.orderNo, provider, attemptKey(provider)) }
  } catch (e) {if(provider==='wallet')creditQuote.value=null; error.value = e instanceof Error ? e.message : 'Unable to start this payment.' }
  finally { busy.value = false }
}
async function saveProof() {
  busy.value = true; error.value = ''; message.value = ''
  try { await submitPaymentProof(props.order.orderNo, proof.value); message.value = 'Payment proof submitted for verification.'; emit('refresh') }
  catch (e) { error.value = e instanceof Error ? e.message : 'Unable to submit payment proof.' }
  finally { busy.value = false }
}
watch(() => props.order.orderNo, () => {
  checkout.value = null
  creditQuote.value = null
  capturedReturnKey.value = ''
  void refreshPaymentData(true)
  void capturePaypalReturn()
}, { immediate: true })
watch(() => props.refreshTick, (tick, previous) => {
  if (tick && tick !== previous) void refreshPaymentData()
})
watch(() => [route.query.token, route.query.payment], () => { void capturePaypalReturn() })
</script>
<template>
  <section id="payment" class="form-section action-section no-print">
    <h2>Payment · {{ formatMoney(order.totalAmount, order.currencyCode) }}</h2>
    <p v-if="error || paymentDataError" class="form-error" role="alert">{{ error || paymentDataError }}</p><p v-if="message" role="status">{{ message }}</p>
    <div v-if="merchant" class="wallet-payment"><p>Enterprise credits: <strong>{{formatPoints(balance)}}</strong></p><p v-if="validCredit&&creditQuote">{{formatMoney(creditQuote.quote.sourceAmount,creditQuote.quote.sourceCurrency)}} → <strong>{{formatPoints(creditQuote.quote.points)}}</strong> · Rate version {{creditQuote.quote.settingsVersion}}</p><p v-else>Refresh the credit quote before payment.</p><button type="button" class="btn-primary" :disabled="busy||loading||!validCredit||!creditQuote?.sufficient" @click="pay('wallet')">Pay with enterprise credits</button><button type="button" class="btn-secondary" :disabled="busy||loading" @click="refreshPaymentData(true)">Refresh credit quote</button><router-link class="btn-secondary" to="/account/company">Top up credits</router-link></div>
    <p v-if="loading" class="muted-copy">Loading available payment methods…</p>
    <div v-else-if="options.length" class="portal-form compact-form"><label>Online payment<select v-model="selectedProvider"><option v-for="option in options" :key="option.provider" :value="option.provider">{{ option.displayName }}{{ option.mode === 'sandbox' ? ' (test mode)' : '' }}</option></select></label><button class="btn-primary" :disabled="busy || !selectedProvider" @click="pay(selectedProvider)">{{ busy ? 'Please wait…' : 'Continue to payment' }}</button></div>
    <p v-else class="muted-copy">Online payment is not currently available for {{ order.currencyCode }}. Contact NXR for transfer instructions.</p>
    <button v-if="paypalReturnKey && error" class="btn-secondary" :disabled="busy" @click="capturePaypalReturn">Retry payment confirmation</button>
    <div v-if="checkout" class="payment-session-panel"><PortalQrCode v-if="checkout.qrPayload" :value="checkout.qrPayload" label="Scan to pay" /><p v-if="checkout.qrPayload">Scan with {{ options.find(option => option.provider === checkout?.provider)?.displayName }}. This order updates after payment is confirmed.</p><a v-if="safePaymentUrl" class="btn-primary" :href="safePaymentUrl" rel="noopener noreferrer">Continue to PayPal</a><button class="btn-secondary" :disabled="busy" @click="checkPaymentStatus">Check payment status</button></div>
    <details class="manual-proof"><summary>Already paid by transfer?</summary><p class="muted-copy">Enter your transfer details for NXR to verify.</p><form class="portal-form compact-form" @submit.prevent="saveProof"><label>Method<select v-model="proof.provider"><option value="bank_transfer">Bank transfer</option><option value="wechat_transfer">WeChat transfer</option><option value="alipay_transfer">Alipay transfer</option><option value="manual_transfer">Other agreed transfer</option></select></label><div class="form-grid"><label>Transfer reference<input v-model="proof.payerReference" required maxlength="255" /></label><label>Receipt reference (optional)<input v-model="proof.proofReference" maxlength="512" /></label></div><button class="btn-secondary" :disabled="busy">{{ busy ? 'Submitting…' : 'Submit payment proof' }}</button></form></details>
  </section>
</template>
<style scoped>.wallet-payment{display:flex;align-items:center;flex-wrap:wrap;gap:12px;padding-bottom:20px}.wallet-payment p{flex-basis:100%;margin:0}.manual-proof{margin-top:26px}.manual-proof summary{cursor:pointer;font-weight:600}.payment-session-panel{margin-top:20px;display:flex;align-items:flex-start;flex-direction:column;gap:12px}</style>
