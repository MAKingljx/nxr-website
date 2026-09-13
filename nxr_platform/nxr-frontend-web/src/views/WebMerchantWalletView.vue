<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import { customerSession, refreshCustomerSession } from '../lib/customer'
import { fetchMerchantProfile, saveMerchantProfile, fetchWallets, fetchWalletTransactions, fetchRecharges, requestRecharge, type Wallet, type WalletTransaction, type Recharge, type Page } from '../lib/merchant'
import { formatMoney, orderStatusLabel } from '../lib/orderProgress'

const router = useRouter()
const company = reactive({ companyName: '', contactName: '' })
const wallets = ref<Wallet[]>([])
const transactions = ref<Page<WalletTransaction>>({ items: [], page: 1, pageSize: 20, total: 0 })
const recharges = ref<Page<Recharge>>({ items: [], page: 1, pageSize: 20, total: 0 })
const currency = ref('USD')
const busy = ref(false)
const loading = ref(true)
const error = ref('')
const success = ref('')
const form = reactive({ currencyCode: 'USD', amount: '', providerCode: 'bank_transfer', payerReference: '', proofReference: '' })
const merchant = computed(() => customerSession.value?.customer.accountTypeCode === 'merchant')
const currencies = ['USD', 'CNY', 'EUR', 'GBP', 'HKD', 'JPY', 'CAD', 'AUD', 'SGD']
const dateLabel = (value: string) => new Date(value).toLocaleString()

async function loadTransactions(page = 1) {
  try { transactions.value = await fetchWalletTransactions(currency.value, page) }
  catch (e) { error.value = e instanceof Error ? e.message : 'Unable to load transactions.' }
}
async function loadRecharges(page = 1) {
  try { recharges.value = await fetchRecharges(page) }
  catch (e) { error.value = e instanceof Error ? e.message : 'Unable to load recharges.' }
}
async function refresh() {
  error.value = ''
  loading.value = true
  try {
    const [profile, balances] = await Promise.all([fetchMerchantProfile(), fetchWallets()])
    Object.assign(company, profile)
    wallets.value = balances
    await Promise.all([loadTransactions(), loadRecharges()])
  } catch (e) { error.value = e instanceof Error ? e.message : 'Unable to load your company account.' }
  finally { loading.value = false }
}
async function saveCompany() {
  busy.value = true; error.value = ''; success.value = ''
  try { Object.assign(company, await saveMerchantProfile(company)); success.value = 'Company details saved.' }
  catch (e) { error.value = e instanceof Error ? e.message : 'Unable to save company details.' }
  finally { busy.value = false }
}
async function recharge() {
  busy.value = true; error.value = ''; success.value = ''
  try {
    await requestRecharge(form)
    form.amount = ''; form.payerReference = ''; form.proofReference = ''
    success.value = 'Recharge submitted. Your balance will update after payment is verified.'
    await loadRecharges()
  } catch (e) { error.value = e instanceof Error ? e.message : 'Unable to submit this recharge.' }
  finally { busy.value = false }
}
onMounted(async () => {
  if (!customerSession.value) { await router.replace('/account/login?next=/account/company'); return }
  const refreshed = await refreshCustomerSession()
  if (!refreshed) {
    if (!customerSession.value) await router.replace('/account/login?next=/account/company')
    else { error.value = 'Unable to verify your session. Check your connection and try again.'; loading.value = false }
    return
  }
  if (merchant.value) await refresh()
  else loading.value = false
})
</script>

<template>
  <LegacySiteNav active="account" cta-href="/submit/order" cta-label="New order" />
  <main class="portal-page">
    <div class="portal-heading"><div><p class="section-tag">Company account</p><h1>Company & prepaid balance</h1><p>Top up your account and pay for grading orders in the same currency.</p></div><div class="form-row form-actions"><router-link class="btn-secondary" to="/account/orders">My orders</router-link><router-link v-if="merchant" class="btn-secondary" to="/account/merchant-orders">Bulk orders</router-link></div></div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="success" class="notice-success" role="status">{{ success }}</p>
    <p v-if="loading" class="portal-empty">Loading company account…</p>
    <p v-else-if="!merchant" class="portal-empty">Company features are available after NXR enables your company account. Please contact support.</p>
    <template v-else>
      <section class="form-section"><div class="wallet-heading"><h2>Available balance</h2><button class="btn-secondary" :disabled="loading" @click="refresh">Refresh</button></div><div class="wallet-balances"><article v-for="code in currencies" :key="code"><span>{{ code }}</span><strong>{{ formatMoney(wallets.find(wallet => wallet.currencyCode === code)?.balance || 0, code) }}</strong></article></div><p class="muted-copy">Each currency has its own balance. Orders use the balance in their payment currency.</p></section>
      <div class="detail-grid-section">
        <section class="form-section"><h2>Company details</h2><form class="portal-form" @submit.prevent="saveCompany"><label>Company name<input v-model="company.companyName" required maxlength="191" /></label><label>Contact name<input v-model="company.contactName" required maxlength="128" /></label><button class="btn-primary" :disabled="busy">Save company details</button></form></section>
        <section class="form-section"><h2>Submit a recharge</h2><p class="muted-copy">Use the payment instructions provided by NXR, then enter the transfer reference for verification.</p><form class="portal-form" @submit.prevent="recharge"><div class="form-grid"><label>Currency<select v-model="form.currencyCode"><option v-for="code in currencies" :key="code">{{ code }}</option></select></label><label>Amount<input v-model="form.amount" type="number" :min="form.currencyCode === 'JPY' ? 1 : 0.01" :step="form.currencyCode === 'JPY' ? 1 : 0.01" max="99999999" required /></label></div><label>Transfer method<select v-model="form.providerCode"><option value="bank_transfer">Bank transfer</option><option value="wechat_transfer">WeChat transfer</option><option value="alipay_transfer">Alipay transfer</option></select></label><label>Transfer reference<input v-model="form.payerReference" required maxlength="255" /></label><label>Receipt reference (optional)<input v-model="form.proofReference" maxlength="512" /></label><button class="btn-primary" :disabled="busy">{{ busy ? 'Please wait…' : 'Submit for verification' }}</button></form></section>
      </div>
      <section class="form-section"><h2>Recharge history</h2><p v-if="!recharges.items.length" class="muted-copy">No recharges yet.</p><div v-else class="table-scroll"><table class="portal-table"><thead><tr><th>Recharge</th><th>Amount</th><th>Status</th><th>Date</th><th>Review</th></tr></thead><tbody><tr v-for="item in recharges.items" :key="item.id"><td>{{ item.rechargeNo }}</td><td>{{ formatMoney(item.amount, item.currencyCode) }}</td><td>{{ orderStatusLabel(item.statusCode) }}</td><td>{{ dateLabel(item.createdAt) }}</td><td>{{ item.reviewNote || '—' }}</td></tr></tbody></table></div><div v-if="recharges.total > recharges.pageSize" class="wallet-heading"><button class="btn-secondary" :disabled="recharges.page === 1" @click="loadRecharges(recharges.page - 1)">Previous</button><span>Page {{ recharges.page }}</span><button class="btn-secondary" :disabled="recharges.page * recharges.pageSize >= recharges.total" @click="loadRecharges(recharges.page + 1)">Next</button></div></section>
      <section class="form-section"><div class="wallet-heading"><h2>Balance transactions</h2><select v-model="currency" aria-label="Transaction currency" @change="loadTransactions()"><option v-for="code in currencies" :key="code">{{ code }}</option></select></div><p v-if="!transactions.items.length" class="muted-copy">No transactions in {{ currency }}.</p><div v-else class="table-scroll"><table class="portal-table"><thead><tr><th>Date</th><th>Type</th><th>Amount</th><th>Balance after</th><th>Details</th></tr></thead><tbody><tr v-for="item in transactions.items" :key="item.id"><td>{{ dateLabel(item.createdAt) }}</td><td>{{ orderStatusLabel(item.transactionTypeCode) }}</td><td>{{ item.directionCode === 'debit' ? '−' : '+' }}{{ formatMoney(item.amount, currency) }}</td><td>{{ formatMoney(item.balanceAfter, currency) }}</td><td>{{ item.note || '—' }}</td></tr></tbody></table></div><div v-if="transactions.total > transactions.pageSize" class="wallet-heading"><button class="btn-secondary" :disabled="transactions.page === 1" @click="loadTransactions(transactions.page - 1)">Previous</button><span>Page {{ transactions.page }}</span><button class="btn-secondary" :disabled="transactions.page * transactions.pageSize >= transactions.total" @click="loadTransactions(transactions.page + 1)">Next</button></div></section>
    </template>
  </main><LegacySiteFooter />
</template>
<style scoped>
.wallet-heading{display:flex;justify-content:space-between;align-items:center;gap:12px}.wallet-heading select{padding:8px 12px;background:var(--bg3);color:var(--text);border:1px solid var(--border2)}.wallet-heading h2{margin:0 0 14px}.wallet-balances{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}.wallet-balances article{padding:20px;border:1px solid var(--border2);border-radius:8px}.wallet-balances span,.wallet-balances strong{display:block}.wallet-balances span{font-size:12px;color:var(--text2);margin-bottom:6px}.wallet-balances strong{font-size:20px}.notice-success{padding:14px;background:#0e1a12;color:#8ed5a6;border-radius:8px}@media(max-width:640px){.wallet-balances{grid-template-columns:1fr 1fr}.wallet-balances strong{font-size:16px}}
</style>
