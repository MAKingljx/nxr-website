<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import { customerSession, refreshCustomerSession } from '../lib/customer'
import { fetchMerchantProfile, saveMerchantProfile, fetchEnterpriseCredit, fetchCreditQuote, sameCreditAmount, fetchWalletTransactions, fetchRecharges, requestRecharge, type CreditQuote, type EnterpriseCreditSummary, type WalletTransaction, type Recharge, type Page } from '../lib/merchant'
import { formatMoney, formatPoints, orderStatusLabel } from '../lib/orderProgress'

const router = useRouter()
const company = reactive({ companyName: '', contactName: '' })
const credit = ref<EnterpriseCreditSummary | null>(null)
const transactions = ref<Page<WalletTransaction>>({ items: [], page: 1, pageSize: 20, total: 0 })
const recharges = ref<Page<Recharge>>({ items: [], page: 1, pageSize: 20, total: 0 })
const currency = ref('PTS')
const busy = ref(false)
const loading = ref(true)
const error = ref('')
const success = ref('')
const form = reactive({ currencyCode: 'CNY', amount: '', providerCode: 'bank_transfer', payerReference: '', proofReference: '' })
const merchant = computed(() => customerSession.value?.customer.accountTypeCode === 'merchant')
const currencies = ['USD', 'CNY', 'EUR', 'GBP', 'HKD', 'JPY', 'CAD', 'AUD', 'SGD']
const creditQuote = ref<CreditQuote | null>(null), quoteLoading=ref(false), quoteError=ref('')
const validQuote = computed(() => Boolean(creditQuote.value && !quoteLoading.value && creditQuote.value.sourceCurrency===form.currencyCode && sameCreditAmount(creditQuote.value.sourceAmount,form.amount)))
const legacy = computed(() => credit.value?.legacyBalances.filter(item=>Number(item.balance)!==0)||[])
const ledgerCurrencies = ['PTS',...currencies]
let quoteGeneration=0, transactionGeneration=0, timer:ReturnType<typeof setTimeout>|undefined, active=true
function invalidateQuote(){quoteGeneration++;clearTimeout(timer);creditQuote.value=null;quoteLoading.value=false}
async function refreshQuote(){invalidateQuote();quoteError.value='';if(!Number.isFinite(Number(form.amount))||Number(form.amount)<=0)return;const current=quoteGeneration,payload={currencyCode:form.currencyCode,amount:form.amount};quoteLoading.value=true;try{const result=await fetchCreditQuote(payload);if(active&&current===quoteGeneration)creditQuote.value=result}catch(e){if(active&&current===quoteGeneration)quoteError.value=e instanceof Error?e.message:'Unable to calculate credits. Refresh the quote and try again.'}finally{if(active&&current===quoteGeneration)quoteLoading.value=false}}
watch(()=>[form.amount,form.currencyCode],()=>{invalidateQuote();quoteError.value='';if(Number(form.amount)>0){quoteLoading.value=true;timer=setTimeout(refreshQuote,250)}},{flush:'sync'})
onBeforeUnmount(()=>{active=false;invalidateQuote();transactionGeneration++})
const dateLabel = (value: string) => new Date(value).toLocaleString()

async function loadTransactions(page = 1) {
  const current=++transactionGeneration
  try { const result=await fetchWalletTransactions(currency.value, page);if(current===transactionGeneration)transactions.value=result }
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
    const [profile, balances] = await Promise.all([fetchMerchantProfile(), fetchEnterpriseCredit()])
    Object.assign(company, profile)
    credit.value = balances
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
  if(!validQuote.value||!creditQuote.value)return
  const payload={...form,settingsVersion:creditQuote.value.settingsVersion}
  busy.value = true; error.value = ''; success.value = ''
  try {
    await requestRecharge(payload)
    form.amount = ''; form.payerReference = ''; form.proofReference = ''
    success.value = 'Recharge submitted. Your balance will update after payment is verified.'
    await loadRecharges()
  } catch (e) { invalidateQuote();error.value = e instanceof Error ? e.message : 'Unable to submit this recharge.' }
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
    <div class="portal-heading"><div><p class="section-tag">Company account</p><h1>Company & enterprise credits</h1><p>Top up in a supported currency and pay for grading orders from one enterprise credit balance.</p></div><div class="form-row form-actions"><router-link class="btn-secondary" to="/account/orders">My orders</router-link><router-link v-if="merchant" class="btn-secondary" to="/account/merchant-orders">Bulk orders</router-link></div></div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="success" class="notice-success" role="status">{{ success }}</p>
    <p v-if="loading" class="portal-empty">Loading company account…</p>
    <p v-else-if="!merchant" class="portal-empty">Company features are available after NXR enables your company account. Please contact support.</p>
    <template v-else>
      <section class="form-section"><div class="wallet-heading"><h2>Available enterprise credits</h2><button class="btn-secondary" :disabled="loading||busy" @click="refresh">Refresh</button></div><strong class="credit-total">{{credit?formatPoints(credit.balance):'—'}}</strong><p class="muted-copy">One credit balance for all supported payment currencies.</p><details v-if="legacy.length"><summary>Historical currency balances</summary><p>Historical balances remain separate until NXR confirms their conversion. Past transactions are retained.</p><p v-for="item in legacy" :key="item.currencyCode">{{formatMoney(item.balance,item.currencyCode)}}</p></details></section>
      <div class="detail-grid-section">
        <section class="form-section"><h2>Company details</h2><form class="portal-form" @submit.prevent="saveCompany"><label>Company name<input v-model="company.companyName" required maxlength="191" /></label><label>Contact name<input v-model="company.contactName" required maxlength="128" /></label><button class="btn-primary" :disabled="busy">Save company details</button></form></section>
        <section class="form-section"><h2>Submit a recharge</h2><p class="muted-copy">Use the payment instructions provided by NXR, then enter the transfer reference for verification.</p><form class="portal-form" @submit.prevent="recharge"><div class="form-grid"><label>Currency<select v-model="form.currencyCode"><option v-for="code in currencies" :key="code">{{ code }}</option></select></label><label>Amount<input v-model="form.amount" type="number" :min="form.currencyCode === 'JPY' ? 1 : 0.01" :step="form.currencyCode === 'JPY' ? 1 : 0.01" max="99999999" required /></label></div><label>Transfer method<select v-model="form.providerCode"><option value="bank_transfer">Bank transfer</option><option value="wechat_transfer">WeChat transfer</option><option value="alipay_transfer">Alipay transfer</option></select></label><label>Transfer reference<input v-model="form.payerReference" required maxlength="255" /></label><label>Receipt reference (optional)<input v-model="form.proofReference" maxlength="512" /></label><div class="credit-quote" aria-live="polite"><p v-if="quoteLoading">Calculating credits…</p><template v-if="validQuote&&creditQuote"><strong>{{formatMoney(creditQuote.sourceAmount,creditQuote.sourceCurrency)}} → {{formatPoints(creditQuote.points)}}</strong><p>Rate version {{creditQuote.settingsVersion}} · 1 {{creditQuote.sourceCurrency}} = {{creditQuote.cnyPerUnit}} CNY · 1 CNY = {{creditQuote.pointsPerCny}} PTS</p></template><p v-if="quoteError" class="form-error">{{quoteError}}</p><button type="button" class="btn-secondary" :disabled="busy||quoteLoading||!Number(form.amount)" @click="refreshQuote">Refresh credit quote</button></div><button class="btn-primary" :disabled="busy||!validQuote">{{ busy ? 'Please wait…' : 'Submit for verification' }}</button></form></section>
      </div>
      <section class="form-section"><h2>Recharge history</h2><p v-if="!recharges.items.length" class="muted-copy">No recharges yet.</p><div v-else class="table-scroll"><table class="portal-table"><thead><tr><th>Recharge</th><th>Payment amount</th><th>Enterprise credits</th><th>Status</th><th>Date</th><th>Review</th></tr></thead><tbody><tr v-for="item in recharges.items" :key="item.id"><td>{{ item.rechargeNo }}</td><td>{{ formatMoney(item.amount, item.currencyCode) }}</td><td>{{item.creditQuote?formatPoints(item.creditQuote.points):'Historical currency record'}}</td><td>{{ orderStatusLabel(item.statusCode) }}</td><td>{{ dateLabel(item.createdAt) }}</td><td>{{ item.reviewNote || '—' }}</td></tr></tbody></table></div><div v-if="recharges.total > recharges.pageSize" class="wallet-heading"><button class="btn-secondary" :disabled="recharges.page === 1" @click="loadRecharges(recharges.page - 1)">Previous</button><span>Page {{ recharges.page }}</span><button class="btn-secondary" :disabled="recharges.page * recharges.pageSize >= recharges.total" @click="loadRecharges(recharges.page + 1)">Next</button></div></section>
      <section class="form-section"><div class="wallet-heading"><h2>Credit and historical transactions</h2><select v-model="currency" aria-label="Transaction currency" @change="loadTransactions()"><option v-for="code in ledgerCurrencies" :key="code" :value="code">{{code==='PTS'?'Enterprise credits':`Historical ${code}`}}</option></select></div><p v-if="!transactions.items.length" class="muted-copy">No transactions in {{ currency }}.</p><div v-else class="table-scroll"><table class="portal-table"><thead><tr><th>Date</th><th>Type</th><th>Amount</th><th>Balance after</th><th>Original payment</th><th>Details</th></tr></thead><tbody><tr v-for="item in transactions.items" :key="item.id"><td>{{ dateLabel(item.createdAt) }}</td><td>{{ orderStatusLabel(item.transactionTypeCode) }}</td><td>{{ item.directionCode === 'debit' ? '−' : '+' }}{{ formatMoney(item.amount, item.currencyCode||currency) }}</td><td>{{ formatMoney(item.balanceAfter, item.currencyCode||currency) }}</td><td>{{item.sourceCurrency&&item.sourceAmount!=null?formatMoney(item.sourceAmount,item.sourceCurrency):'—'}}</td><td>{{ item.note || '—' }}</td></tr></tbody></table></div><div v-if="transactions.total > transactions.pageSize" class="wallet-heading"><button class="btn-secondary" :disabled="transactions.page === 1" @click="loadTransactions(transactions.page - 1)">Previous</button><span>Page {{ transactions.page }}</span><button class="btn-secondary" :disabled="transactions.page * transactions.pageSize >= transactions.total" @click="loadTransactions(transactions.page + 1)">Next</button></div></section>
    </template>
  </main><LegacySiteFooter />
</template>
<style scoped>
.credit-total{font-size:32px}.credit-quote{padding:16px;border:1px solid var(--border2);border-radius:8px;margin:12px 0}
.wallet-heading{display:flex;justify-content:space-between;align-items:center;gap:12px}.wallet-heading select{padding:8px 12px;background:var(--bg3);color:var(--text);border:1px solid var(--border2)}.wallet-heading h2{margin:0 0 14px}.wallet-balances{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}.wallet-balances article{padding:20px;border:1px solid var(--border2);border-radius:8px}.wallet-balances span,.wallet-balances strong{display:block}.wallet-balances span{font-size:12px;color:var(--text2);margin-bottom:6px}.wallet-balances strong{font-size:20px}.notice-success{padding:14px;background:#0e1a12;color:#8ed5a6;border-radius:8px}@media(max-width:640px){.wallet-balances{grid-template-columns:1fr 1fr}.wallet-balances strong{font-size:16px}}
</style>
