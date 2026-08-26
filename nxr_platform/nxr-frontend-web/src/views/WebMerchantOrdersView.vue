<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import {
  createMerchantOrders,
  customerSession,
  downloadMerchantOrderTemplate,
  fetchCustomerAddresses,
  type CustomerAddress,
} from '../lib/customer'

type ParsedRow = {
  rowNo: number
  languageCode: string
  quantity: number
  returnAddressId: number
  returnShippingOptionCode: string
  customerNote: string
  error: string
}

const router = useRouter()
const addresses = ref<CustomerAddress[]>([])
const fileName = ref('')
const rows = ref<ParsedRow[]>([])
const submitting = ref(false)
const errorMessage = ref('')
const result = ref<Awaited<ReturnType<typeof createMerchantOrders>> | null>(null)
const validRows = computed(() => rows.value.filter((row) => !row.error))

function parseCsvLine(line: string) {
  const values: string[] = []
  let value = ''
  let quoted = false
  for (let index = 0; index < line.length; index += 1) {
    const char = line[index]
    if (char === '"' && quoted && line[index + 1] === '"') {
      value += '"'
      index += 1
    } else if (char === '"') quoted = !quoted
    else if (char === ',' && !quoted) {
      values.push(value.trim())
      value = ''
    } else value += char
  }
  values.push(value.trim())
  return values
}

function parseCsv(content: string) {
  const lines = content.replace(/^\uFEFF/, '').split(/\r?\n/).filter((line) => line.trim())
  const headers = parseCsvLine(lines.shift() || '').map((header) => header.toLowerCase())
  const required = ['language_code', 'quantity', 'return_address_id', 'return_shipping_option_code']
  if (required.some((header) => !headers.includes(header))) throw new Error(`CSV headers must include: ${required.join(', ')}`)
  rows.value = lines.slice(0, 200).map((line, index) => {
    const values = parseCsvLine(line)
    const cell = (name: string) => values[headers.indexOf(name)] || ''
    const quantity = Number(cell('quantity'))
    const returnAddressId = Number(cell('return_address_id'))
    const addressExists = addresses.value.some((address) => address.id === returnAddressId)
    const problems = [
      !cell('language_code') ? 'language_code is required' : '',
      !Number.isInteger(quantity) || quantity < 1 || quantity > 30 ? 'quantity must be 1–30' : '',
      !Number.isInteger(returnAddressId) || !addressExists ? 'return_address_id is not in your address book' : '',
      !cell('return_shipping_option_code') ? 'return_shipping_option_code is required' : '',
    ].filter(Boolean)
    return {
      rowNo: index + 2,
      languageCode: cell('language_code').toUpperCase(),
      quantity,
      returnAddressId,
      returnShippingOptionCode: cell('return_shipping_option_code'),
      customerNote: cell('customer_note'),
      error: problems.join('; '),
    }
  })
  if (lines.length > 200) throw new Error('A single import can contain at most 200 orders.')
}

async function selectFile(event: Event) {
  result.value = null
  errorMessage.value = ''
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  fileName.value = file.name
  try {
    parseCsv(await file.text())
  } catch (error) {
    rows.value = []
    errorMessage.value = error instanceof Error ? error.message : 'Unable to read this CSV file.'
  }
}

async function downloadTemplate() {
  try {
    const content = await downloadMerchantOrderTemplate()
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
  if (!validRows.value.length) return
  submitting.value = true
  errorMessage.value = ''
  try {
    result.value = await createMerchantOrders({
      sourceName: fileName.value,
      orders: validRows.value.map((row) => ({
        serviceLevel: 'basic_grading',
        returnAddressId: row.returnAddressId,
        saveReturnAddress: false,
        returnShippingOptionCode: row.returnShippingOptionCode,
        contactName: '', contactPhone: '', returnAddressLine1: '', returnAddressLine2: '',
        returnCity: '', returnRegion: '', returnPostalCode: '', returnCountry: '',
        customerNote: row.customerNote,
        languageGroups: [{ languageCode: row.languageCode, quantity: row.quantity }],
        items: [],
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
    addresses.value = await fetchCustomerAddresses()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to load your address book.'
  }
})
</script>

<template>
  <LegacySiteNav active="account" cta-href="/submit/order" cta-label="New order" />
  <main class="portal-page">
    <div class="portal-heading"><div><p class="section-tag">Merchant portal</p><h1>Bulk grading orders</h1><p>Each valid CSV row creates an independent order, quote, payment record, address snapshot and intake barcode.</p></div><router-link class="btn-secondary" to="/account/orders">My orders</router-link></div>
    <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>
    <div v-if="customerSession?.customer.accountTypeCode !== 'merchant'" class="portal-empty">Merchant batch ordering is not enabled for this account. Ask an administrator to change the account type.</div>
    <template v-else>
      <section class="form-section merchant-import-panel">
        <h2>1. Prepare the CSV</h2>
        <p class="muted-copy">Add your saved address ID and one active return-shipping option code to every row. Invalid rows are isolated from valid rows.</p>
        <div class="form-row form-actions"><button type="button" class="btn-secondary" @click="downloadTemplate">Download template</button><router-link class="btn-secondary" to="/account/addresses">Manage addresses</router-link></div>
        <div v-if="addresses.length" class="reference-chips"><span v-for="address in addresses" :key="address.id">Address {{ address.id }}: {{ address.label }}</span></div>
      </section>
      <section class="form-section merchant-import-panel">
        <h2>2. Upload and validate</h2>
        <input type="file" accept=".csv,text/csv" @change="selectFile" />
        <div v-if="rows.length" class="table-scroll"><table class="portal-table"><thead><tr><th>CSV row</th><th>Language</th><th>Qty</th><th>Address</th><th>Return option</th><th>Validation</th></tr></thead><tbody><tr v-for="row in rows" :key="row.rowNo"><td>{{ row.rowNo }}</td><td>{{ row.languageCode }}</td><td>{{ row.quantity }}</td><td>{{ row.returnAddressId }}</td><td>{{ row.returnShippingOptionCode }}</td><td :class="row.error ? 'cell-error' : 'cell-success'">{{ row.error || 'Ready' }}</td></tr></tbody></table></div>
        <button v-if="validRows.length" type="button" class="btn-primary form-submit" :disabled="submitting" @click="submitImport">{{ submitting ? 'Creating orders...' : `Create ${validRows.length} valid order${validRows.length === 1 ? '' : 's'}` }}</button>
      </section>
      <section v-if="result" class="form-section">
        <h2>3. Import result</h2>
        <p class="muted-copy">Job {{ result.jobId }} · {{ result.acceptedRows }} accepted · {{ result.rejectedRows }} rejected</p>
        <div class="table-scroll"><table class="portal-table"><thead><tr><th>Import row</th><th>Status</th><th>Order ID</th><th>Error</th></tr></thead><tbody><tr v-for="row in result.rows" :key="row.rowNo"><td>{{ row.rowNo }}</td><td>{{ row.statusCode }}</td><td>{{ row.orderId || '-' }}</td><td>{{ row.errorMessage || '-' }}</td></tr></tbody></table></div>
      </section>
    </template>
  </main>
  <LegacySiteFooter />
</template>
