<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { fetchCustomerAddresses, fetchServicePrices, fetchShippingOptions, type CustomerAddress } from '../../lib/customer'
import { fetchBatchQuote, fetchOrderQuote, type BatchQuote } from '../../lib/commercePolicy'
import { formatMoney } from '../../lib/orderProgress'
import { createAgentSubmission, useAgentActions, type AgentIntake, type AgentSubmission } from '../../lib/agentWorkbench'
const props = defineProps<{ intakes: AgentIntake[] }>()
const emit = defineEmits<{ created: [submission: AgentSubmission]; close: [] }>()
const addresses = ref<CustomerAddress[]>([]), currencies = ref<string[]>([]), services = ref<Array<{ code: string; name: string }>>([])
const addressId = ref(0), currency = ref(''), optionCode = ref(''), batchName = ref(''), loading = ref(true), quoting = ref(false)
const quote = ref<BatchQuote | null>(null)
const { busy, error, success, run } = useAgentActions()
const totalCards = computed(() => props.intakes.reduce((sum, intake) => sum + intake.expectedCardCount, 0))
let serviceGeneration = 0, quoteGeneration = 0
async function loadServices() {
  const current = ++serviceGeneration
  ++quoteGeneration; quote.value = null; services.value = []; optionCode.value = ''
  const address = addresses.value.find(item => item.id === addressId.value)
  if (!address || !currency.value || !totalCards.value) { loading.value = false; quoting.value = false; return }
  loading.value = true; error.value = ''
  try {
    const [options, initial] = await Promise.all([fetchShippingOptions(address.country, currency.value), fetchOrderQuote(address.country, currency.value, totalCards.value, '')])
    if (current !== serviceGeneration) return
    services.value = options.map(item => ({ code: item.optionCode, name: item.displayName }))
    if (initial.shippingSourceCode === 'weight_policy' && !services.value.some(item => item.code === initial.shippingOptionCode)) services.value.unshift({ code: initial.shippingOptionCode, name: initial.shippingDisplayName })
    optionCode.value = services.value[0]?.code || ''
  } catch (e) { if (current === serviceGeneration) error.value = e instanceof Error ? e.message : '返程服务加载失败。' }
  finally { if (current === serviceGeneration) loading.value = false }
}
async function loadQuote() {
  const current = ++quoteGeneration
  quote.value = null
  const address = addresses.value.find(item => item.id === addressId.value)
  if (!address || !currency.value || !optionCode.value || !props.intakes.length) { quoting.value = false; return }
  quoting.value = true
  try {
    const result = await fetchBatchQuote(address.country, currency.value, optionCode.value, props.intakes.map(intake => ({ reference: intake.intakeNo, cardCount: intake.expectedCardCount })))
    if (current === quoteGeneration) quote.value = result
  } catch (e) { if (current === quoteGeneration) error.value = e instanceof Error ? e.message : '报价加载失败。' }
  finally { if (current === quoteGeneration) quoting.value = false }
}
watch([addressId, currency, totalCards], loadServices)
watch(optionCode, loadQuote)
async function submit() {
  if (!quote.value || loading.value || quoting.value || !props.intakes.length) return
  const payload = { intakeIds: props.intakes.map(intake => intake.id).sort((a, b) => a - b), returnAddressId: addressId.value, returnShippingOptionCode: optionCode.value, currencyCode: currency.value, batchName: batchName.value.trim(), quotedTotalAmount: Number(quote.value.aggregate.totalAmount), quotedCurrencyCode: currency.value }
  const saved = await run('submission:create', payload, key => createAgentSubmission(payload, key), value => emit('created', value), '送评批次已生成。')
  if (!saved) quote.value = null
}
onMounted(async () => {
  try { const [saved, prices] = await Promise.all([fetchCustomerAddresses(), fetchServicePrices()]); addresses.value = saved; currencies.value = [...new Set(prices.map(price => price.currencyCode))]; addressId.value = saved.find(address => address.defaultAddress)?.id || saved[0]?.id || 0; currency.value = currencies.value.includes('USD') ? 'USD' : currencies.value[0] || '' }
  catch (e) { error.value = e instanceof Error ? e.message : '返程信息加载失败。' }
  finally { loading.value = false }
})
</script>
<template><form class="portal-form agent-submission-form" data-testid="agent-submission-form" @submit.prevent="submit"><div class="agent-toolbar"><h2>生成送评批次</h2><button type="button" class="text-button" :disabled="busy" @click="emit('close')">返回清点</button></div><p class="muted-copy">{{ intakes.length }} 件来件 · {{ totalCards }} 张卡 · 基础评级</p><p class="muted-copy">NXR 统一寄回代理地址；批次生成后继续审核、确认及余额付款。</p><p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="success" class="agent-success" role="status">{{ success }}</p><fieldset :disabled="busy"><label>批次名称<input v-model="batchName" required maxlength="191" placeholder="例如：九月第一批" data-testid="agent-batch-name" /></label><label>代理返程地址<select v-model="addressId" required data-testid="agent-return-address"><option :value="0" disabled>请选择代理地址</option><option v-for="address in addresses" :key="address.id" :value="address.id">{{ address.label }} · {{ address.contactName }} · {{ address.country }} {{ address.city }} {{ address.addressLine1 }}</option></select></label><router-link to="/account/addresses">管理代理地址</router-link><div class="form-grid"><label>结算币种<select v-model="currency" required><option v-for="code in currencies" :key="code">{{ code }}</option></select></label><label>返程服务<select v-model="optionCode" required :disabled="loading" data-testid="agent-return-service"><option value="" disabled>请选择返程服务</option><option v-for="service in services" :key="service.code" :value="service.code">{{ service.name }}</option></select></label></div></fieldset><p v-if="loading || quoting" class="muted-copy" role="status">加载报价…</p><div v-else-if="quote" class="agent-quote"><span>评级 {{ formatMoney(quote.aggregate.serviceFee, currency) }}</span><span>一件返程运费 {{ formatMoney(quote.aggregate.returnShippingFee, currency) }}</span><strong>合计 {{ formatMoney(quote.aggregate.totalAmount, currency) }}</strong></div><button v-else-if="optionCode" type="button" class="btn-secondary" :disabled="busy" @click="loadQuote">重新加载报价</button><button class="btn-primary" :disabled="busy || loading || quoting || !quote || !intakes.length" data-testid="agent-create-submission">{{ busy ? '生成中…' : '确认生成批次' }}</button></form></template>
