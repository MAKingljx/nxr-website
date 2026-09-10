<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { formatMoney } from '../lib/orderProgress'
import { fetchBatchQuote, fetchOrderQuote, type BatchQuote } from '../lib/commercePolicy'
import { csvRows } from '../lib/csvRows'
import MerchantBatchPanel from '../components/MerchantBatchPanel.vue'
import { createMerchantBatch, localTrackingUrl } from '../lib/merchantBatches'
import { fetchApplicationConfig } from '../lib/orderApplication'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import {
  customerSession,
  fetchCustomerAddresses,
  fetchShippingOptions,
  fetchServicePrices,
  type CustomerAddress,
} from '../lib/customer'

type ParsedRow = {
  rowNo: number
  clientReference: string
  clientDisplayName: string
  cardNames: string[]
  languageCode: string
  quantity: number
  returnAddressId: number
  returnShippingOptionCode: string
  customerNote: string
  currencyCode: string
  error: string
}

const router = useRouter()
const addresses = ref<CustomerAddress[]>([])
const fileName = ref('')
const batchName = ref('')
const maxCards = ref(200)
const quote = ref<BatchQuote | null>(null)
const quoting = ref(false)
const referenceCurrency = ref('USD')
const currencyOptions = ref<string[]>([])
const returnReferences = ref<Array<{ addressId: number; name: string; code: string }>>([])
const rows = ref<ParsedRow[]>([])
const submitting = ref(false)
const errorMessage = ref('')
const result = ref<Awaited<ReturnType<typeof createMerchantBatch>> | null>(null)
const validRows = computed(() => rows.value.filter((row) => !row.error))

function parseCsv(content: string) {
  const lines = csvRows(content)
  const headers = (lines.shift() || []).map(header => header.toLowerCase())
  const required = ['client_reference', 'language_code', 'quantity', 'return_address_id', 'return_shipping_option_code']
  if (required.some((header) => !headers.includes(header))) throw new Error(`CSV headers must include: ${required.join(', ')}`)
  rows.value = lines.slice(0, 200).map((values, index) => {
    const cell = (name: string) => values[headers.indexOf(name)] || ''
    const quantity = Number(cell('quantity'))
    const returnAddressId = Number(cell('return_address_id'))
    const cardNames = cell('card_names').split('|').map(name => name.trim()).filter(Boolean)
    const addressExists = addresses.value.some((address) => address.id === returnAddressId)
    const problems = [
      !cell('client_reference') ? 'client_reference is required' : '',
      !cell('language_code') ? 'language_code is required' : '',
      !Number.isInteger(quantity) || quantity < 1 || quantity > maxCards.value ? `quantity must be 1–${maxCards.value}` : '',
      !Number.isInteger(returnAddressId) || !addressExists ? 'return_address_id is not in your address book' : '',
      cardNames.length > quantity ? 'card_names contains more cards than quantity' : '',
      !cell('return_shipping_option_code') ? 'return_shipping_option_code is required' : '',
    ].filter(Boolean)
    return {
      rowNo: index + 2,
      clientReference: cell('client_reference'),
      clientDisplayName: cell('client_name'),
      cardNames,
      languageCode: cell('language_code').toUpperCase(),
      quantity,
      returnAddressId,
      returnShippingOptionCode: cell('return_shipping_option_code'),
      customerNote: cell('customer_note'),
      currencyCode: cell('currency_code').toUpperCase() || 'USD',
      error: problems.join('; '),
    }
  })
  if (lines.length > 200) throw new Error('A single import can contain at most 200 orders.')
}

async function loadBatchQuote() {
  quote.value = null
  const first = validRows.value[0]
  if (!first) return
  if (validRows.value.some(row => row.returnAddressId !== first.returnAddressId || row.currencyCode !== first.currencyCode || row.returnShippingOptionCode !== first.returnShippingOptionCode)) {
    errorMessage.value = 'A batch must use one agent return address, currency and return service.'
    return
  }
  if (new Set(validRows.value.map(row => row.clientReference)).size !== validRows.value.length) {
    errorMessage.value = 'Each customer reference must be unique within a batch.'
    return
  }
  const address = addresses.value.find(address => address.id === first.returnAddressId)
  if (!address) return
  quoting.value = true
  try { quote.value = await fetchBatchQuote(address.country, first.currencyCode, first.returnShippingOptionCode, validRows.value.map(row => ({ reference: row.clientReference, cardCount: row.quantity }))) }
  catch (e) { errorMessage.value = e instanceof Error ? e.message : 'Unable to calculate this batch quote.' }
  finally { quoting.value = false }
}

async function selectFile(event: Event) {
  result.value = null
  quote.value = null
  errorMessage.value = ''
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  if (file.size > 2 * 1024 * 1024) { errorMessage.value = 'The CSV file must be 2 MB or smaller.'; return }
  fileName.value = file.name
  try {
    parseCsv(await file.text())
    await loadBatchQuote()
  } catch (error) {
    rows.value = []
    errorMessage.value = error instanceof Error ? error.message : 'Unable to read this CSV file.'
  }
}

async function loadReturnReferences() {
  returnReferences.value = []
  try {
    const results = await Promise.all(addresses.value.map(async address => {
      const [options, defaultQuote] = await Promise.all([fetchShippingOptions(address.country, referenceCurrency.value), fetchOrderQuote(address.country, referenceCurrency.value, 1, '')])
      const mapped = options.map(option => ({ addressId: address.id, name: option.displayName, code: option.optionCode }))
      if (defaultQuote.shippingSourceCode === 'weight_policy') mapped.unshift({ addressId: address.id, name: defaultQuote.shippingDisplayName, code: defaultQuote.shippingOptionCode })
      return mapped
    }))
    returnReferences.value = results.flat()
  } catch (e) { errorMessage.value = e instanceof Error ? e.message : 'Return services are unavailable for this currency.' }
}

async function downloadTemplate() {
  try {
    const addressId = addresses.value[0]?.id || 'YOUR_ADDRESS_ID'
    const optionCode = returnReferences.value.find(option => option.addressId === addresses.value[0]?.id)?.code || 'RETURN_OPTION_CODE'
    const content = '\uFEFFclient_reference,client_name,card_names,language_code,quantity,return_address_id,return_shipping_option_code,currency_code,customer_note\nCLIENT-001,Customer one,Pikachu|Charizard,EN,2,' + addressId + ',' + optionCode + ',' + referenceCurrency.value + ',Keep in separate inner package\n'
    const url = URL.createObjectURL(new Blob([content], { type: 'text/csv;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = 'nxr-merchant-order-template.csv'
    link.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to download the template.'
  }
}

async function submitImport() {
  if (!validRows.value.length || !quote.value || quoting.value || submitting.value || result.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    result.value = await createMerchantBatch({
      sourceName: fileName.value,
      batchName: batchName.value || fileName.value,
      expectedTotalAmount: Number(quote.value.aggregate.totalAmount),
      orders: validRows.value.map((row) => ({
        clientReference: row.clientReference,
        clientDisplayName: row.clientDisplayName,
        clientContactHint: '',
        order: {
        serviceLevel: 'basic_grading',
        currencyCode: row.currencyCode,
        quotedTotalAmount: Number(quote.value!.allocations.find(part => part.reference === row.clientReference)!.totalAmount),
        quotedCurrencyCode: row.currencyCode,
        returnAddressId: row.returnAddressId,
        saveReturnAddress: false,
        returnShippingOptionCode: row.returnShippingOptionCode,
        contactName: '', contactPhone: '', returnAddressLine1: '', returnAddressLine2: '',
        returnCity: '', returnRegion: '', returnPostalCode: '', returnCountry: '',
        customerNote: row.customerNote,
        languageGroups: [],
        items: Array.from({ length: row.quantity }, (_, index) => ({ cardName: row.cardNames[index] || `${row.clientReference} card ${index + 1}`, languageCode: row.languageCode, productType: 'graded_card', category: 'trading_card' })),
        },
      })),
    })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to import merchant orders.'
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  if (!customerSession.value) {
    await router.replace('/account/login?next=/account/merchant-orders')
    return
  }
  if (customerSession.value.customer.accountTypeCode !== 'merchant') return
  try {
    const [savedAddresses, config, prices] = await Promise.all([fetchCustomerAddresses(), fetchApplicationConfig(), fetchServicePrices()])
    addresses.value = savedAddresses
    maxCards.value = config.maxCardsPerOrder
    currencyOptions.value = prices.map(price => price.currencyCode)
    await loadReturnReferences()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to load your address book.'
  }
})
</script>

<template>
  <LegacySiteNav active="account" cta-href="/submit/order" cta-label="New order" />
  <main class="portal-page">
    <div class="portal-heading"><div><p class="section-tag">Merchant portal</p><h1>Bulk grading orders</h1><p>Group separate customer orders into one shipment to NXR, with private progress links for each customer.</p></div><router-link class="btn-secondary" to="/account/orders">My orders</router-link></div>
    <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>
    <div v-if="customerSession?.customer.accountTypeCode !== 'merchant'" class="portal-empty">Merchant batch ordering is not enabled for this account. Ask an administrator to change the account type.</div>
    <template v-else>
      <section class="form-section merchant-import-panel">
        <h2>1. Prepare the CSV</h2>
        <p class="muted-copy">Each row creates a customer order. Use the same saved agent return address throughout a batch. Separate card names with |; each quantity represents physical cards. Invalid rows are reported individually.</p>
        <label>Template currency<select v-model="referenceCurrency" @change="loadReturnReferences"><option v-for="currency in currencyOptions" :key="currency">{{ currency }}</option></select></label>
        <div class="form-row form-actions"><button type="button" class="btn-secondary" @click="downloadTemplate">Download template</button><router-link class="btn-secondary" to="/account/addresses">Manage addresses</router-link></div>
        <div v-if="addresses.length" class="reference-chips"><span v-for="address in addresses" :key="address.id">Address {{ address.id }}: {{ address.label }}</span></div>
        <div class="reference-chips"><span v-for="option in returnReferences" :key="`${option.addressId}:${option.code}`">Address {{ option.addressId }} · {{ option.name }} · Code: {{ option.code }}</span></div>
      </section>
      <section class="form-section merchant-import-panel">
        <h2>2. Upload and validate</h2>
        <label>Batch name<input v-model="batchName" maxlength="191" placeholder="e.g. September customer shipment" /></label>
        <input type="file" accept=".csv,text/csv" @change="selectFile" />
        <div v-if="rows.length" class="table-scroll"><table class="portal-table"><thead><tr><th>CSV row</th><th>Customer reference</th><th>Language</th><th>Qty</th><th>Address</th><th>Return option</th><th>Validation</th></tr></thead><tbody><tr v-for="row in rows" :key="row.rowNo"><td>{{ row.rowNo }}</td><td>{{ row.clientReference }}</td><td>{{ row.languageCode }}</td><td>{{ row.quantity }}</td><td>{{ row.returnAddressId }}</td><td>{{ row.returnShippingOptionCode }}</td><td :class="row.error ? 'cell-error' : 'cell-success'">{{ row.error || 'Ready' }}</td></tr></tbody></table></div>
        <p v-if="quoting" class="muted-copy">Calculating the batch quote…</p>
        <div v-if="quote" class="quote-panel"><h3>Batch quote</h3><p>{{ quote.aggregate.cardCount }} cards · Grading {{ formatMoney(quote.aggregate.serviceFee, quote.aggregate.currencyCode) }} · One return parcel {{ formatMoney(quote.aggregate.returnShippingFee, quote.aggregate.currencyCode) }}</p><strong>Total {{ formatMoney(quote.aggregate.totalAmount, quote.aggregate.currencyCode) }}</strong><p class="muted-copy">The return fee is shared across child orders. Each order requires review and confirmation before payment.</p><div v-for="part in quote.allocations" :key="part.reference">{{ part.reference }} · {{ formatMoney(part.totalAmount, quote.aggregate.currencyCode) }}</div></div>
        <button v-if="validRows.length" type="button" class="btn-primary form-submit" :disabled="submitting || quoting || !quote || Boolean(result)" @click="submitImport">{{ result ? 'Batch created' : submitting ? 'Creating orders...' : `Create ${validRows.length} valid order${validRows.length === 1 ? '' : 's'}` }}</button>
      </section>
      <section v-if="result" class="form-section">
        <h2>3. Import result</h2>
        <p class="muted-copy">Batch {{ result.batchNo }} · {{ result.acceptedRows }} accepted · {{ result.rejectedRows }} rejected</p>
        <div class="table-scroll"><table class="portal-table"><thead><tr><th>Import row</th><th>Status</th><th>Order</th><th>Private tracking link</th><th>Error</th></tr></thead><tbody><tr v-for="row in result.rows" :key="row.rowNo"><td>{{ row.rowNo }}</td><td>{{ row.statusCode }}</td><td><router-link v-if="row.orderNo" :to="`/account/orders/${row.orderNo}`">{{ row.orderNo }}</router-link><span v-else>—</span></td><td><input v-if="row.trackingToken" :value="localTrackingUrl(row.trackingToken)" readonly aria-label="Private tracking link" @focus="($event.target as HTMLInputElement).select()" /></td><td>{{ row.errorMessage || '-' }}</td></tr></tbody></table></div>
      </section>
      <MerchantBatchPanel :refresh-key="result?.batchNo" />
    </template>
  </main>
  <LegacySiteFooter />
</template>
