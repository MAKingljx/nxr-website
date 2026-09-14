<script setup lang="ts">
import { tx } from '@/i18n'
import { useAgentApi } from '../lib/agentWorkbench'
import { computed, onMounted, ref, watch } from 'vue'
import type { CustomerAddress } from '../lib/agentWorkbench'
import type { BatchQuote } from '../lib/agentWorkbench'
import { formatMoney } from '../lib/agentWorkbench'
import { useAgentActions, type AgentIntake, type AgentSubmission } from '../lib/agentWorkbench'
const api = useAgentApi()
const { fetchCustomerAddresses, fetchServicePrices, fetchShippingOptions, fetchBatchQuote, fetchOrderQuote } = api
const { createAgentSubmission } = api

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
  } catch (e) { if (current === serviceGeneration) error.value = e instanceof Error ? e.message : tx('Unable to load return services.') }
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
  } catch (e) { if (current === quoteGeneration) error.value = e instanceof Error ? e.message : tx('Unable to load quote.') }
  finally { if (current === quoteGeneration) quoting.value = false }
}
watch([addressId, currency, totalCards], loadServices)
watch(optionCode, loadQuote)
async function submit() {
  if (!quote.value || loading.value || quoting.value || !props.intakes.length) return
  const payload = { intakeIds: props.intakes.map(intake => intake.id).sort((a, b) => a - b), returnAddressId: addressId.value, returnShippingOptionCode: optionCode.value, currencyCode: currency.value, batchName: batchName.value.trim(), quotedTotalAmount: Number(quote.value.aggregate.totalAmount), quotedCurrencyCode: currency.value }
  const saved = await run('submission:create', payload, key => createAgentSubmission(payload, key), value => emit('created', value), tx('Submission batch created.'))
  if (!saved) quote.value = null
}
onMounted(async () => {
  try { const [saved, prices] = await Promise.all([fetchCustomerAddresses(), fetchServicePrices()]); addresses.value = saved; currencies.value = [...new Set(prices.map(price => price.currencyCode))]; addressId.value = saved.find(address => address.defaultAddress)?.id || saved[0]?.id || 0; currency.value = currencies.value.includes('USD') ? 'USD' : currencies.value[0] || '' }
  catch (e) { error.value = e instanceof Error ? e.message : tx('Unable to load return shipping details.') }
  finally { loading.value = false }
})
</script>
<template><form class="portal-form agent-submission-form" data-testid="agent-submission-form" @submit.prevent="submit"><div class="agent-toolbar"><h2>{{ $tx('Create submission batch') }}</h2><button type="button" class="text-button" :disabled="busy" @click="emit('close')">{{ $tx('Back to intake check') }}</button></div><p class="muted-copy">{{ $tx('{p1} intakes · {p2} cards · Standard grading', { p1: intakes.length, p2: totalCards }) }}</p><p class="muted-copy">{{ $tx('NXR returns the batch to the sub-agent address. After creating the batch, complete review, confirmation and wallet payment.') }}</p><p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="success" class="agent-success" role="status">{{ success }}</p><fieldset :disabled="busy"><label>{{ $tx('Batch name') }}<input v-model="batchName" required maxlength="191" :placeholder="$tx('e.g. September batch 1')" data-testid="agent-batch-name" /></label><label>{{ $tx('Sub-agent return address') }}<select v-model="addressId" required data-testid="agent-return-address"><option :value="0" disabled>{{ $tx('Select a sub-agent address') }}</option><option v-for="address in addresses" :key="address.id" :value="address.id">{{ address.label }} · {{ address.contactName }} · {{ address.country }} {{ address.city }} {{ address.addressLine1 }}</option></select></label><router-link :to="{ path: '/nxr/submission-workbench', query: { tab: 'addresses', company: api.companyId } }">{{ $tx('Manage sub-agent addresses') }}</router-link><div class="form-grid"><label>{{ $tx('Settlement currency') }}<select v-model="currency" required><option v-for="code in currencies" :key="code">{{ code }}</option></select></label><label>{{ $tx('Return service') }}<select v-model="optionCode" required :disabled="loading" data-testid="agent-return-service"><option value="" disabled>{{ $tx('Select a return service') }}</option><option v-for="service in services" :key="service.code" :value="service.code">{{ service.name }}</option></select></label></div></fieldset><p v-if="loading || quoting" class="muted-copy" role="status">{{ $tx('Loading quote…') }}</p><div v-else-if="quote" class="agent-quote"><span>{{ $tx('Grading: {p1}', { p1: formatMoney(quote.aggregate.serviceFee, currency) }) }}</span><span>{{ $tx('Single-parcel return shipping: {p1}', { p1: formatMoney(quote.aggregate.returnShippingFee, currency) }) }}</span><strong>{{ $tx('Total: {p1}', { p1: formatMoney(quote.aggregate.totalAmount, currency) }) }}</strong></div><button v-else-if="optionCode" type="button" class="btn-secondary" :disabled="busy" @click="loadQuote">{{ $tx('Reload quote') }}</button><button class="btn-primary" :disabled="busy || loading || quoting || !quote || !intakes.length" data-testid="agent-create-submission">{{ busy ? $tx('Creating…') : $tx('Confirm and create batch') }}</button></form></template>
