<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { formatMoney } from '../lib/orderProgress'
import { fetchOrderQuote, type OrderQuote } from '../lib/commercePolicy'
import OrderCardEditor from '../components/OrderCardEditor.vue'
import UnusedOrderPhotos from '../components/UnusedOrderPhotos.vue'
import { emptyApplicationCard, fetchApplicationConfig } from '../lib/orderApplication'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import {
  createGradingOrder,
  customerSession,
  fetchCustomerAddresses,
  fetchServicePrices,
  type ServicePrice,
  fetchShippingOptions,
  type CustomerAddress,
  type ShippingOption,
} from '../lib/customer'

const router = useRouter()
const errorMessage = ref('')
const submitting = ref(false)
const loadingReferenceData = ref(true)
const addresses = ref<CustomerAddress[]>([])
const shippingOptions = ref<ShippingOption[]>([])
const servicePrice = ref({ unitPrice: 0, currencyCode: 'USD', displayName: 'Basic grading' })
const servicePrices = ref<ServicePrice[]>([])
const selectedCurrency = ref('USD')
const selectedAddressId = ref('')
const selectedShippingOptionCode = ref('')
const maxCards = ref(200)
const uploadingPhotos = ref(false)
const quotes = ref<Record<string, OrderQuote>>({})
const quoting = ref(false)
let quoteGeneration = 0
let optionGeneration = 0

const form = reactive({
  contactName: '',
  contactPhone: '',
  returnAddressLine1: '',
  returnAddressLine2: '',
  returnCity: '',
  returnRegion: '',
  returnPostalCode: '',
  returnCountry: '',
  saveReturnAddress: true,
  customerNote: '',
  items: [emptyApplicationCard()],
})

const cardCount = computed(() => form.items.length)
const selectedQuote = computed(() => quotes.value[selectedShippingOptionCode.value] || null)
const serviceFee = computed(() => Number(selectedQuote.value?.serviceFee || 0))
const quotedTotal = computed(() => Number(selectedQuote.value?.totalAmount || 0))

async function loadQuotes() {
  const generation = ++quoteGeneration
  quoting.value = true; quotes.value = {}
  if (!form.returnCountry.trim() || !shippingOptions.value.length || !cardCount.value) { quoting.value = false; return }
  try {
    const entries = await Promise.all(shippingOptions.value.map(async option => [option.optionCode, await fetchOrderQuote(form.returnCountry, selectedCurrency.value, cardCount.value, option.optionCode)] as const))
    if (generation !== quoteGeneration) return
    quotes.value = Object.fromEntries(entries); errorMessage.value = ''
  } catch (e) { if (generation === quoteGeneration) errorMessage.value = e instanceof Error ? e.message : 'Unable to calculate the current quote.' }
  finally { if (generation === quoteGeneration) quoting.value = false }
}
watch([cardCount, selectedCurrency, () => form.returnCountry, shippingOptions], () => { void loadQuotes() })

async function loadShippingOptions() {
  const generation = ++optionGeneration
  if (!form.returnCountry.trim()) { shippingOptions.value = []; selectedShippingOptionCode.value = ''; return }
  try {
    const [fixedOptions, defaultQuote] = await Promise.all([
      fetchShippingOptions(form.returnCountry, selectedCurrency.value),
      fetchOrderQuote(form.returnCountry, selectedCurrency.value, cardCount.value, ''),
    ])
    if (generation !== optionGeneration) return
    const options = [...fixedOptions]
    if (defaultQuote.shippingSourceCode === 'weight_policy') options.unshift({
      id: 0, optionCode: defaultQuote.shippingOptionCode, displayName: defaultQuote.shippingDisplayName,
      description: 'Calculated from packaged card weight and destination.', countryScope: form.returnCountry,
      currencyCode: defaultQuote.currencyCode, priceAmount: defaultQuote.returnShippingFee, sortOrder: -1, active: true,
    })
    shippingOptions.value = options
    if (!options.some(item => item.optionCode === selectedShippingOptionCode.value)) selectedShippingOptionCode.value = options[0]?.optionCode || ''
  } catch (error) {
    if (generation !== optionGeneration) return
    shippingOptions.value = []; selectedShippingOptionCode.value = ''
    errorMessage.value = error instanceof Error ? error.message : 'Unable to load return shipping options.'
  }
}

async function selectCurrency() {
  const price = servicePrices.value.find(item => item.currencyCode === selectedCurrency.value)
  if (!price) return
  servicePrice.value = price
  selectedShippingOptionCode.value = ''
  shippingOptions.value = []
  await loadShippingOptions()
}

async function applyAddress() {
  const address = addresses.value.find((item) => String(item.id) === selectedAddressId.value)
  if (address) {
    form.contactName = address.contactName
    form.contactPhone = address.contactPhone
    form.returnAddressLine1 = address.addressLine1
    form.returnAddressLine2 = address.addressLine2 || ''
    form.returnCity = address.city
    form.returnRegion = address.region || ''
    form.returnPostalCode = address.postalCode
    form.returnCountry = address.country
    form.saveReturnAddress = false
  }
  await loadShippingOptions()
}

async function submitOrder() {
  errorMessage.value = ''
  if (cardCount.value < 1 || cardCount.value > maxCards.value) {
    errorMessage.value = `The order must contain between 1 and ${maxCards.value} cards.`
    return
  }
  if (form.items.some(item => !item.cardName.trim())) {
    errorMessage.value = 'Add a name or description for each physical card.'
    return
  }
  if (!selectedShippingOptionCode.value) {
    errorMessage.value = 'Choose an available return shipping option.'
    return
  }
  if (quoting.value || !selectedQuote.value) { errorMessage.value = 'Please wait for the current quote before submitting.'; return }
  submitting.value = true
  try {
    const order = await createGradingOrder({
      serviceLevel: 'basic_grading',
      currencyCode: selectedCurrency.value,
      quotedTotalAmount: Number(selectedQuote.value.totalAmount),
      quotedCurrencyCode: selectedQuote.value.currencyCode,
      returnAddressId: selectedAddressId.value ? Number(selectedAddressId.value) : null,
      saveReturnAddress: !selectedAddressId.value && form.saveReturnAddress,
      returnShippingOptionCode: selectedShippingOptionCode.value,
      contactName: form.contactName,
      contactPhone: form.contactPhone,
      returnAddressLine1: form.returnAddressLine1,
      returnAddressLine2: form.returnAddressLine2,
      returnCity: form.returnCity,
      returnRegion: form.returnRegion,
      returnPostalCode: form.returnPostalCode,
      returnCountry: form.returnCountry,
      customerNote: form.customerNote,
      languageGroups: [],
      items: form.items.map(({ localId, ...item }) => ({ ...item, languageCode: item.languageCode.trim().toUpperCase() })),
    })
    await router.push(`/account/orders/${encodeURIComponent(order.orderNo)}`)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to create the grading order.'
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  if (!customerSession.value) {
    await router.replace('/account/login?next=/submit/order')
    return
  }
  form.contactName = customerSession.value.customer.displayName
  form.contactPhone = customerSession.value.customer.mobile || ''
  try {
    const [savedAddresses, prices, config] = await Promise.all([
      fetchCustomerAddresses(),
      fetchServicePrices(),
      fetchApplicationConfig(),
    ])
    maxCards.value = config.maxCardsPerOrder
    addresses.value = savedAddresses
    servicePrices.value = prices
    const price = prices.find(item => item.currencyCode === 'USD') || prices[0]
    if (!price) throw new Error('Grading prices are not available yet. Please contact NXR.')
    servicePrice.value = price
    selectedCurrency.value = price.currencyCode
    const defaultAddress = savedAddresses.find((item) => item.defaultAddress)
    if (defaultAddress) {
      selectedAddressId.value = String(defaultAddress.id)
      await applyAddress()
    } else {
      await loadShippingOptions()
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to prepare the order form.'
  } finally {
    loadingReferenceData.value = false
  }
})
</script>

<template>
  <LegacySiteNav active="submit" />
  <main class="portal-page order-create-page">
    <div class="portal-heading">
      <div>
        <p class="section-tag">Grading applications · Public beta</p>
        <h1>Send cards to NXR</h1>
        <p>Add your cards and return address. NXR reviews the application before you confirm the order and pay.</p>
      </div>
      <router-link class="btn-secondary" to="/account/orders">My orders</router-link>
    </div>
    <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>
    <div v-if="loadingReferenceData" class="portal-empty">Loading pricing and addresses...</div>
    <form v-else class="portal-form order-form" @submit.prevent="submitOrder">
      <section class="form-section">
        <h2>Basic grading</h2><label>Payment currency<select v-model="selectedCurrency" @change="selectCurrency"><option v-for="price in servicePrices" :key="price.currencyCode" :value="price.currencyCode">{{ price.currencyCode }}</option></select></label>
        <p class="muted-copy">{{ formatMoney(selectedQuote?.unitPrice || servicePrice.unitPrice, servicePrice.currencyCode) }} per card. Choose a currency and return delivery option for your order.</p>
      </section>

      <OrderCardEditor v-model="form.items" :max-cards="maxCards" @busy="uploadingPhotos = $event" />
      <UnusedOrderPhotos :current-ids="form.items.flatMap(item => [item.frontPhotoId, item.backPhotoId]).filter((id): id is number => id !== null)" />

      <section class="form-section">
        <h2>Return address</h2>
        <label v-if="addresses.length">Saved address
          <select v-model="selectedAddressId" @change="applyAddress">
            <option value="">Use a new address</option>
            <option v-for="address in addresses" :key="address.id" :value="String(address.id)">{{ address.label }} · {{ address.contactName }} · {{ address.country }}</option>
          </select>
        </label>
        <div class="form-grid">
          <label>Contact name<input v-model="form.contactName" required maxlength="128" :disabled="Boolean(selectedAddressId)" /></label>
          <label>Phone<input v-model="form.contactPhone" required maxlength="64" :disabled="Boolean(selectedAddressId)" /></label>
          <label class="form-wide">Address line 1<input v-model="form.returnAddressLine1" required maxlength="255" :disabled="Boolean(selectedAddressId)" /></label>
          <label class="form-wide">Address line 2<input v-model="form.returnAddressLine2" maxlength="255" :disabled="Boolean(selectedAddressId)" /></label>
          <label>City<input v-model="form.returnCity" required maxlength="128" :disabled="Boolean(selectedAddressId)" /></label>
          <label>Region / state<input v-model="form.returnRegion" maxlength="128" :disabled="Boolean(selectedAddressId)" /></label>
          <label>Postal code<input v-model="form.returnPostalCode" required maxlength="64" :disabled="Boolean(selectedAddressId)" /></label>
          <label>Country / region<input v-model="form.returnCountry" required maxlength="128" placeholder="US, CN, HK…" :disabled="Boolean(selectedAddressId)" @blur="loadShippingOptions" /></label>
        </div>
        <label v-if="!selectedAddressId" class="check-label"><input v-model="form.saveReturnAddress" type="checkbox" /> Save this address for future orders</label>
      </section>

      <section class="form-section">
        <h2>Prepaid return shipping</h2>
        <p v-if="!shippingOptions.length" class="muted-copy">No return option is available for the selected country.</p>
        <div v-else class="shipping-option-grid">
          <label v-for="option in shippingOptions" :key="option.optionCode" :class="{ selected: selectedShippingOptionCode === option.optionCode }">
            <input v-model="selectedShippingOptionCode" type="radio" :value="option.optionCode" />
            <strong>{{ option.displayName }}</strong>
            <span>{{ option.description }}</span>
            <b>{{ quotes[option.optionCode] ? formatMoney(quotes[option.optionCode]!.returnShippingFee, option.currencyCode) : 'Calculating…' }}</b>
          </label>
        </div>
      </section>

      <section class="form-section quote-panel">
        <h2>Order total</h2>
        <div><span>Basic grading ({{ cardCount }} × {{ formatMoney(selectedQuote?.unitPrice || servicePrice.unitPrice, servicePrice.currencyCode) }})</span><strong>{{ formatMoney(serviceFee, servicePrice.currencyCode) }}</strong></div>
        <div><span>Prepaid return shipping</span><strong>{{ formatMoney(selectedQuote?.returnShippingFee || 0, servicePrice.currencyCode) }}</strong></div>
        <div class="quote-total"><span>Total</span><strong>{{ !form.returnCountry.trim() ? 'Enter the destination country' : quoting || !selectedQuote ? 'Calculating…' : formatMoney(quotedTotal, servicePrice.currencyCode) }}</strong></div>
      </section>

      <p class="muted-copy">No payment is taken when you apply. Incoming postage is paid by the sender. The total includes the selected return delivery service.</p>
      <label>Order note<textarea v-model="form.customerNote" maxlength="2000" /></label>
      <button class="btn-primary form-submit" type="submit" :disabled="submitting || uploadingPhotos || quoting || !selectedQuote || !shippingOptions.length">
        {{ uploadingPhotos ? 'Uploading images…' : submitting ? 'Submitting application…' : 'Submit application for review' }}
      </button>
    </form>
  </main>
  <LegacySiteFooter />
</template>
