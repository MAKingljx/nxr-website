<script setup lang="ts">
import { tx } from '@/i18n'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import type { EnterpriseCreditSummary } from '@/api/nxr/enterpriseCredit'
import { useEnterpriseCreditQuote } from '@/composables/useEnterpriseCreditQuote'
import { agentDateLabel, agentStatusLabel, emptyAgentPage, formatMoney, formatPoints, useAgentApi, useAgentActions, type AgentTransaction, type AgentRecharge } from '../lib/agentWorkbench'
import AgentPagination from './AgentPagination.vue'
const api = useAgentApi(), credit = ref<EnterpriseCreditSummary | null>(null), transactions = ref(emptyAgentPage<AgentTransaction>()), recharges = ref(emptyAgentPage<AgentRecharge>())
const route = useRoute()
const currencies = ['CNY','USD','EUR','GBP','HKD','JPY','CAD','AUD','SGD']
const initialCurrency = currencies.includes(String(route.query.currency)) ? String(route.query.currency) : 'CNY'
const profile = reactive({ companyName: '', contactName: '' }), currency = ref('PTS'), loading = ref(false)
const ledgerCurrencies = ['PTS', ...currencies]
const form = reactive({ currencyCode: initialCurrency, amount: '', providerCode: 'bank_transfer', payerReference: '', proofReference: '' })
const { busy, error, success, run } = useAgentActions()
const quote = useEnterpriseCreditQuote(() => ({ currencyCode: form.currencyCode, amount: form.amount }), api.fetchCreditQuote)
const legacy = computed(() => credit.value?.legacyBalances.filter(item => Number(item.balance) !== 0) || [])
let transactionGeneration = 0
async function loadTransactions(page = 1) { const current=++transactionGeneration; try { const result=await api.fetchTransactions(currency.value, page);if(current===transactionGeneration)transactions.value=result } catch (e) { if(current===transactionGeneration)error.value = e instanceof Error ? e.message : tx('Unable to load wallet transactions.') } }
async function loadRecharges(page = 1) { try { recharges.value = await api.fetchRecharges(page) } catch (e) { error.value = e instanceof Error ? e.message : tx('Unable to load top-up history.') } }
async function load() { loading.value = true; try { const [company, summary] = await Promise.all([api.fetchMerchantProfile(),api.fetchEnterpriseCredit()]); Object.assign(profile,company); credit.value = summary; await Promise.all([loadTransactions(),loadRecharges()]) } catch (e) { error.value = e instanceof Error ? e.message : tx('Unable to load company balances.') } finally { loading.value = false } }
async function saveProfile() { const payload = { ...profile }; await run('profile:save',payload,() => api.saveMerchantProfile(payload),() => {},tx('Company profile saved.')) }
async function recharge() {
  if (!quote.valid.value || !quote.quote.value) return
  const payload = { ...form, settingsVersion: quote.quote.value.settingsVersion }
  if (await run('recharge:create',payload,key => api.requestRecharge(payload,key),() => { form.amount='';form.payerReference='';form.proofReference='' },tx('Top-up submitted. Credits will be added after payment verification.'))) await loadRecharges()
  else quote.invalidate()
}
onMounted(load)
</script>
<template>
  <section data-testid="agent-wallet-panel">
    <div class="agent-toolbar"><h2>{{ $tx('Enterprise credits') }}</h2><el-button :loading="loading" :disabled="busy" @click="load">{{ $tx('Refresh') }}</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" /><el-alert v-if="success" :title="success" type="success" :closable="false" />
    <div class="agent-quote"><span>{{ $tx('Available enterprise credits') }}</span><strong data-testid="enterprise-credit-balance">{{ credit ? formatPoints(credit.balance) : '—' }}</strong><span>{{ $tx('One credit balance for all supported payment currencies.') }}</span></div>
    <details v-if="legacy.length" class="agent-operation"><summary>{{ $tx('Historical currency balances') }}</summary><p>{{ $tx('Historical balances remain separate until NXR confirms their conversion. Past transactions are retained.') }}</p><p v-for="item in legacy" :key="item.currencyCode">{{ formatMoney(item.balance,item.currencyCode) }}</p></details>
    <div class="form-grid">
      <form class="portal-form agent-operation" @submit.prevent="saveProfile"><h3>{{ $tx('Company profile') }}</h3><label>{{ $tx('Company name') }}<input v-model="profile.companyName" required maxlength="191" :disabled="busy" /></label><label>{{ $tx('Contact person') }}<input v-model="profile.contactName" required maxlength="128" :disabled="busy" /></label><button class="btn-primary" :disabled="busy">{{ $tx('Save profile') }}</button></form>
      <form class="portal-form agent-operation" data-testid="agent-recharge-form" @submit.prevent="recharge"><h3>{{ $tx('Request top-up') }}</h3><p class="muted-copy">{{ $tx('Transfer funds using the payment details provided by NXR, then submit your payment reference for verification.') }}</p><fieldset :disabled="busy"><div class="form-grid"><label>{{ $tx('Payment currency') }}<select v-model="form.currencyCode"><option v-for="code in currencies" :key="code">{{ code }}</option></select></label><label>{{ $tx('Amount') }}<input v-model="form.amount" type="number" required :min="form.currencyCode === 'JPY' ? 1 : .01" :step="form.currencyCode === 'JPY' ? 1 : .01" max="99999999" /></label></div><label>{{ $tx('Transfer method') }}<select v-model="form.providerCode"><option value="bank_transfer">{{ $tx('Bank transfer') }}</option><option value="wechat_transfer">{{ $tx('WeChat transfer') }}</option><option value="alipay_transfer">{{ $tx('Alipay transfer') }}</option></select></label><label>{{ $tx('Transfer reference') }}<input v-model="form.payerReference" required maxlength="255" /></label><label>{{ $tx('Payment proof details (optional)') }}<input v-model="form.proofReference" maxlength="512" /></label></fieldset>
        <div class="agent-quote" aria-live="polite"><span v-if="quote.loading.value">{{ $tx('Calculating credits…') }}</span><template v-if="quote.valid.value && quote.quote.value"><strong>{{ formatMoney(quote.quote.value.sourceAmount, quote.quote.value.sourceCurrency) }} → {{ formatPoints(quote.quote.value.points) }}</strong><span>{{ $tx('Rate version {version}', {version:quote.quote.value.settingsVersion}) }} · 1 {{quote.quote.value.sourceCurrency}} = {{quote.quote.value.cnyPerUnit}} CNY · 1 CNY = {{quote.quote.value.pointsPerCny}} PTS</span></template><span v-if="quote.error.value" role="alert">{{quote.error.value}}</span><el-button :disabled="busy||quote.loading.value||!Number(form.amount)" @click="quote.refresh">{{ $tx('Refresh credit quote') }}</el-button></div>
        <button class="btn-primary" :disabled="busy||!quote.valid.value" data-testid="agent-submit-recharge">{{ $tx('Submit for payment verification') }}</button>
      </form>
    </div>
    <h3>{{ $tx('Top-up history') }}</h3><el-table :data="recharges.items" border><el-table-column prop="rechargeNo" :label="$tx('Top-up reference')" min-width="180" /><el-table-column :label="$tx('Payment amount')" min-width="130"><template #default="{row}">{{ formatMoney(row.amount,row.currencyCode) }}</template></el-table-column><el-table-column :label="$tx('Enterprise credits')" min-width="160"><template #default="{row}">{{row.creditQuote ? formatPoints(row.creditQuote.points) : $tx('Historical currency record')}}</template></el-table-column><el-table-column :label="$tx('Status')" min-width="130"><template #default="{row}">{{ agentStatusLabel(row.statusCode) }}</template></el-table-column><el-table-column prop="reviewNote" :label="$tx('Payment verification notes')" min-width="180" /><el-table-column :label="$tx('Time')" min-width="170"><template #default="{row}">{{ agentDateLabel(row.createdAt) }}</template></el-table-column></el-table><AgentPagination v-bind="recharges" :disabled="busy" @change="loadRecharges" />
    <div class="agent-toolbar"><h3>{{ $tx('Credit and historical transactions') }}</h3><el-select v-model="currency" style="width:200px" @change="loadTransactions()"><el-option v-for="code in ledgerCurrencies" :key="code" :value="code" :label="code === 'PTS' ? $tx('Enterprise credits') : $tx('Historical {currency}',{currency:code})" /></el-select></div><el-table :data="transactions.items" border><el-table-column :label="$tx('Time')" min-width="170"><template #default="{row}">{{ agentDateLabel(row.createdAt) }}</template></el-table-column><el-table-column :label="$tx('Amount')" min-width="130"><template #default="{row}">{{ row.directionCode === 'debit' ? '−' : '+' }}{{ formatMoney(row.amount,row.currencyCode||currency) }}</template></el-table-column><el-table-column :label="$tx('Balance')" min-width="130"><template #default="{row}">{{ formatMoney(row.balanceAfter,row.currencyCode||currency) }}</template></el-table-column><el-table-column :label="$tx('Original payment')" min-width="150"><template #default="{row}">{{row.sourceCurrency && row.sourceAmount != null ? formatMoney(row.sourceAmount,row.sourceCurrency) : '—'}}</template></el-table-column><el-table-column prop="note" :label="$tx('Description')" min-width="220" /></el-table><AgentPagination v-bind="transactions" :disabled="busy" @change="loadTransactions" />
  </section>
</template>
