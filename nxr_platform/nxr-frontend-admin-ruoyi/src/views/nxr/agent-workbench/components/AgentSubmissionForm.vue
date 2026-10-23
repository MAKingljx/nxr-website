<script setup lang="ts">
import AgentAddressesPanel from './AgentAddressesPanel.vue'
import { tx, localizeBackendMessage, localizeOrderTerms } from '@/i18n'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useAgentApi, useAgentActions, type AgentIntake, type AgentSubmission, type AgentLogisticsOption, type CustomerAddress, type WorkbenchOrder } from '../lib/agentWorkbench'
const api = useAgentApi()
const props = defineProps<{ intakes?: AgentIntake[]; orders?: WorkbenchOrder[] }>()
const parts = computed(() => props.orders ? props.orders.map(order => ({ id: order.id, reference: order.orderNo, cardCount: order.cardCount })) : (props.intakes || []).map(intake => ({ id: intake.id, reference: intake.intakeNo, cardCount: intake.expectedCardCount })))
const emit = defineEmits<{ created: [submission: AgentSubmission]; close: [] }>()
const addressOpen = ref(false), addresses = ref<CustomerAddress[]>([]), services = ref<AgentLogisticsOption[]>([])
const addressId = ref(0), serviceKey = ref(''), batchName = ref(''), loading = ref(true)
const addressTouched = ref(false)
const terms = ref<{ termsVersion: string; termsText: string; turnaroundText: string } | null>(null)
const acceptedTerms = ref(false), loadingTerms = ref(true), termsError = ref(''), termsOpen = ref(false)
const { busy, error, success, run } = useAgentActions()
const totalCards = computed(() => parts.value.reduce((sum, part) => sum + part.cardCount, 0))
const ready = computed(() => !loading.value && !loadingTerms.value && Boolean(terms.value) && acceptedTerms.value && Boolean(batchName.value.trim()) && addressId.value > 0 && Boolean(services.value.find(option => optionKey(option) === serviceKey.value)) && parts.value.length > 0)
const optionKey = (option: AgentLogisticsOption) => `${option.currencyCode}:${option.optionCode}`
const selectedService = computed(() => services.value.find(option => optionKey(option) === serviceKey.value))
let serviceGeneration = 0, addressGeneration = 0, active = true
let termsGeneration = 0
async function loadTerms() {
  const current = ++termsGeneration
  acceptedTerms.value = false; loadingTerms.value = true; termsError.value = ''; terms.value = null
  try {
    const config = await api.fetchApplicationConfig()
    if (current !== termsGeneration || !active) return
    if (!config.termsVersion?.trim() || !config.termsText?.trim()) throw new Error(tx('Service terms are unavailable. Please reload before submitting.'))
    terms.value = { termsVersion: config.termsVersion, termsText: config.termsText, turnaroundText: config.turnaroundText || '' }
  } catch (e) { if (current === termsGeneration && active) termsError.value = e instanceof Error ? e.message : tx('Unable to load service terms.') }
  finally { if (current === termsGeneration && active) loadingTerms.value = false }
}
async function loadServices() {
  const current = ++serviceGeneration
  const previous = serviceKey.value
  services.value = []; serviceKey.value = ''
  const address = addresses.value.find(item => item.id === addressId.value)
  if (!address || !totalCards.value) { loading.value = false; return }
  loading.value = true; error.value = ''
  try {
    const options = await api.fetchLogisticsOptions(address.country, totalCards.value)
    if (current !== serviceGeneration || !active) return
    services.value = options
    serviceKey.value = options.some(option => optionKey(option) === previous) ? previous : options[0] ? optionKey(options[0]) : ''
  } catch (e) { if (current === serviceGeneration && active) error.value = e instanceof Error ? e.message : tx('Unable to load return services.') }
  finally { if (current === serviceGeneration && active) loading.value = false }
}
watch([addressId, totalCards], loadServices)
watch(() => parts.value.map(part => `${part.id}:${part.cardCount}`).join('|'), () => { acceptedTerms.value = false })
async function submit() {
  if (busy.value || !ready.value || !selectedService.value || !terms.value) return
  const payload = { intakeIds: parts.value.map(part => part.id).sort((a, b) => a - b), returnAddressId: addressId.value, returnShippingOptionCode: selectedService.value.optionCode, currencyCode: selectedService.value.currencyCode, batchName: batchName.value.trim(), termsAccepted: true, ...terms.value }
  let created: AgentSubmission | null = null
  const saved = await run('submission:create', payload, key => api.createAgentSubmission(payload, key), value => { created = value }, tx('Submit created.'))
  if (saved && created) emit('created', created)
}
async function reloadAddresses() {
  const current = ++addressGeneration
  loading.value = true
  try {
    const saved = await api.fetchCustomerAddresses()
    if (!active || current !== addressGeneration) return
    addresses.value = saved
    const nextId = !addressTouched.value || !saved.some(address => address.id === addressId.value) ? saved.find(address => address.defaultAddress)?.id || saved[0]?.id || 0 : addressId.value
    if (nextId !== addressId.value) addressId.value = nextId
    else await loadServices()
    if (!addressId.value) loading.value = false
  } catch (e) { if (active && current === addressGeneration) { error.value = e instanceof Error ? e.message : tx('Unable to load addresses.'); loading.value = false } }
}
watch(api.addressVersion, reloadAddresses)
onMounted(() => Promise.all([reloadAddresses(), loadTerms()]))
onBeforeUnmount(() => { active = false; ++serviceGeneration; ++termsGeneration; ++addressGeneration })
</script>
<template>
  <form class="portal-form agent-submission-form" data-testid="agent-submission-form" @submit.prevent="submit">
    <h3>{{ $tx('Submit details') }}</h3>
    <p class="muted-copy">{{ $tx('{p1} orders · {p2} cards · Standard grading', { p1: parts.length, p2: totalCards }) }}</p>
    <p v-if="error" class="form-error" role="alert">{{ localizeBackendMessage(error) }}</p><p v-if="success" class="agent-success" role="status">{{ localizeBackendMessage(success) }}</p>
    <fieldset :disabled="busy">
      <label><span>{{ $tx('Submit name') }} <b class="required" aria-hidden="true">*</b></span><input v-model="batchName" required maxlength="191" :placeholder="$tx('e.g. September batch 1')" data-testid="agent-batch-name" /></label>
      <label><span>{{ $tx('Company return address') }} <b class="required" aria-hidden="true">*</b></span><select v-model="addressId" required data-testid="agent-return-address" @change="addressTouched = true"><option :value="0" disabled>{{ $tx('Select your company address') }}</option><option v-for="address in addresses" :key="address.id" :value="address.id">{{ address.label }} · {{ address.contactName }} · {{ address.country }} {{ address.city }} {{ address.addressLine1 }}</option></select></label>
      <el-button link type="primary" :disabled="busy" @click="addressOpen = true">{{ $tx('My addresses') }}</el-button>
      <label><span>{{ $tx('Return service') }} <b class="required" aria-hidden="true">*</b></span><select v-model="serviceKey" required :disabled="loading" data-testid="agent-return-service"><option value="" disabled>{{ $tx('Select a return service') }}</option><option v-for="service in services" :key="optionKey(service)" :value="optionKey(service)">{{ $tx(service.displayName) }}</option></select></label>
      <div class="terms-consent">
        <label class="agent-checkbox"><input v-model="acceptedTerms" type="checkbox" required :disabled="loadingTerms || !terms" :aria-label="$tx('I have read and accept the current service terms')" data-testid="agent-submit-terms-checkbox" />{{ $tx('I have read and accept') }}</label>
        <el-button link type="primary" :disabled="loadingTerms || !terms" data-testid="agent-view-submit-terms" @click="termsOpen = true">{{ $tx('Service terms') }}</el-button>
      </div>
      <p v-if="loadingTerms" class="muted-copy" role="status">{{ $tx('Loading service terms…') }}</p>
      <p v-if="termsError" class="form-error" role="alert">{{ localizeBackendMessage(termsError) }} <el-button link type="primary" :disabled="loadingTerms" @click="loadTerms">{{ $tx('Reload service terms') }}</el-button></p>
    </fieldset>
    <p v-if="loading" class="muted-copy" role="status">{{ $tx('Loading return services…') }}</p>
    <el-button v-if="!loading && addressId && !services.length" :disabled="busy" @click="loadServices">{{ $tx('Reload return services') }}</el-button>
    <div class="agent-actions"><button class="btn-primary" :disabled="busy || !ready" data-testid="agent-create-submission">{{ busy ? $tx('Creating…') : $tx('Create Submit') }}</button><el-button :disabled="busy" @click="emit('close')">{{ $tx('Cancel') }}</el-button></div>
  </form>
  <el-drawer v-model="termsOpen" :title="$tx('Service terms')" size="min(640px, 94vw)" append-to-body data-testid="agent-submit-terms-drawer">
    <template v-if="terms"><p class="muted-copy">{{ $tx('Terms version: {p1}', { p1: terms.termsVersion }) }}</p><div class="terms-text"><p>{{ localizeOrderTerms(terms.termsText) }}</p><p v-if="terms.turnaroundText">{{ localizeOrderTerms(terms.turnaroundText) }}</p></div></template>
    <el-button link type="primary" :disabled="loadingTerms || busy" @click="loadTerms">{{ $tx('Reload service terms') }}</el-button>
  </el-drawer>
  <el-drawer v-model="addressOpen" :title="$tx('My addresses')" size="min(900px, 94vw)" :append-to-body="false" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @closed="reloadAddresses"><AgentAddressesPanel v-if="addressOpen" /></el-drawer>
</template>
<style scoped>.agent-submission-form{padding:20px;border:1px solid var(--el-border-color-light);border-radius:8px}.agent-submission-form h3{margin-top:0}.required{color:var(--el-color-danger);margin-left:3px}.terms-consent{display:flex;align-items:center;gap:4px;flex-wrap:wrap;margin-top:16px}.terms-consent :deep(.el-button){padding:0;text-decoration:underline;text-underline-offset:3px}.terms-text{white-space:pre-wrap;overflow-wrap:anywhere}.terms-text p{margin:12px 0;line-height:1.8}.agent-checkbox{display:flex!important;align-items:center;gap:8px;margin:0!important}.agent-checkbox input{width:auto;flex:none;margin:0}@media(max-width:650px){.agent-submission-form{padding:12px}}</style>
