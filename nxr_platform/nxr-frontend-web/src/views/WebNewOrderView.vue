<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import {
  createGradingOrder,
  customerSession,
  fetchCustomerAddresses,
  fetchServicePrice,
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
const selectedAddressId = ref('')
const selectedShippingOptionCode = ref('')

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
  languageGroups: [{ languageCode: 'EN', quantity: 1 }],
})

const cardCount = computed(() => form.languageGroups.reduce((sum, group) => sum + Number(group.quantity || 0), 0))
const selectedShippingOption = computed(() => shippingOptions.value.find((item) => item.optionCode === selectedShippingOptionCode.value) || null)
const serviceFee = computed(() => Number(servicePrice.value.unitPrice || 0) * cardCount.value)
const quotedTotal = computed(() => serviceFee.value + Number(selectedShippingOption.value?.priceAmount || 0))

function addLanguageGroup() {
  if (cardCount.value < 30) form.languageGroups.push({ languageCode: 'EN', quantity: 1 })
}

function removeLanguageGroup(index: number) {
  if (form.languageGroups.length > 1) form.languageGroups.splice(index, 1)
}

async function loadShippingOptions() {
  try {
    shippingOptions.value = await fetchShippingOptions(form.returnCountry)
    if (!shippingOptions.value.some((item) => item.optionCode === selectedShippingOptionCode.value)) {
      selectedShippingOptionCode.value = shippingOptions.value[0]?.optionCode || ''
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to load return shipping options.'
  }
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
  if (cardCount.value < 1 || cardCount.value > 30) {
    errorMessage.value = 'The order must contain between 1 and 30 cards.'
    return
  }
  if (!selectedShippingOptionCode.value) {
    errorMessage.value = 'Choose an available return shipping option.'
    return
  }
  submitting.value = true
  try {
    const order = await createGradingOrder({
      serviceLevel: 'basic_grading',
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
      languageGroups: form.languageGroups.map((group) => ({
        languageCode: group.languageCode.trim().toUpperCase(),
        quantity: Number(group.quantity),
      })),
      items: [],
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
    const [savedAddresses, currentServicePrice] = await Promise.all([
      fetchCustomerAddresses(),
      fetchServicePrice(),
    ])
    addresses.value = savedAddresses
    servicePrice.value = currentServicePrice
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
        <p class="section-tag">New grading order</p>
        <h1>Send cards to NXR</h1>
        <p>Choose card languages and quantities, your return address, and one prepaid return option.</p>
      </div>
      <router-link class="btn-secondary" to="/account/orders">My orders</router-link>
    </div>
    <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>
    <div v-if="loadingReferenceData" class="portal-empty">Loading pricing and addresses...</div>
    <form v-else class="portal-form order-form" @submit.prevent="submitOrder">
      <section class="form-section">
        <h2>Basic grading</h2>
        <p class="muted-copy">{{ servicePrice.currencyCode }} {{ Number(servicePrice.unitPrice).toFixed(2) }} per card. Packaging upgrades and grading rush tiers are intentionally not part of this service.</p>
      </section>

      <section class="form-section">
        <h2>Card languages and quantities</h2>
        <div v-for="(group, index) in form.languageGroups" :key="index" class="order-card-form">
          <div class="form-row">
            <strong>Group {{ index + 1 }}</strong>
            <button v-if="form.languageGroups.length > 1" type="button" class="text-button" @click="removeLanguageGroup(index)">Remove</button>
          </div>
          <div class="form-grid">
            <label>Language code<input v-model="group.languageCode" required maxlength="32" placeholder="EN, JA, ZH..." /></label>
            <label>Quantity<input v-model.number="group.quantity" required type="number" min="1" max="30" /></label>
          </div>
        </div>
        <button v-if="cardCount < 30" type="button" class="btn-secondary" @click="addLanguageGroup">Add language group</button>
        <p class="muted-copy">Total cards: {{ cardCount }} / 30</p>
      </section>

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
          <label>Country<input v-model="form.returnCountry" required maxlength="128" :disabled="Boolean(selectedAddressId)" @blur="loadShippingOptions" /></label>
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
            <b>{{ option.currencyCode }} {{ Number(option.priceAmount).toFixed(2) }}</b>
          </label>
        </div>
      </section>

      <section class="form-section quote-panel">
        <h2>Frozen quote</h2>
        <div><span>Basic grading ({{ cardCount }} × {{ servicePrice.currencyCode }} {{ Number(servicePrice.unitPrice).toFixed(2) }})</span><strong>{{ servicePrice.currencyCode }} {{ serviceFee.toFixed(2) }}</strong></div>
        <div><span>Prepaid return shipping</span><strong>{{ selectedShippingOption?.currencyCode || servicePrice.currencyCode }} {{ Number(selectedShippingOption?.priceAmount || 0).toFixed(2) }}</strong></div>
        <div class="quote-total"><span>Total</span><strong>{{ servicePrice.currencyCode }} {{ quotedTotal.toFixed(2) }}</strong></div>
      </section>

      <label>Order note<textarea v-model="form.customerNote" maxlength="2000" /></label>
      <button class="btn-primary form-submit" type="submit" :disabled="submitting || !shippingOptions.length">
        {{ submitting ? 'Creating order...' : 'Create order and payment record' }}
      </button>
    </form>
  </main>
  <LegacySiteFooter />
</template>
