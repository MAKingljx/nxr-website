<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import {
  createCustomerAddress,
  customerSession,
  deleteCustomerAddress,
  fetchCustomerAddresses,
  updateCustomerAddress,
  type CustomerAddress,
} from '../lib/customer'
import { addressCities, addressCountries, addressRegions, withCurrentOption } from '../lib/addressCatalog'

const router = useRouter()
const addresses = ref<CustomerAddress[]>([])
const editingId = ref<number | null>(null)
const loading = ref(true)
const saving = ref(false)
const errorMessage = ref('')
const form = reactive(emptyAddress())
const countryOptions = computed(() => withCurrentOption(addressCountries, form.country))
const regionOptions = computed(() => withCurrentOption(addressRegions(form.country), form.region))
const cityOptions = computed(() => withCurrentOption(addressCities(form.country, form.region), form.city))
const knownCountry = computed(() => addressRegions(form.country).length > 0)
const knownRegion = computed(() => addressCities(form.country, form.region).length > 0)

function countryChanged() {
  form.region = ''
  form.city = ''
}

function regionChanged() {
  form.city = ''
}

function emptyAddress() {
  return {
    label: 'Return address', contactName: '', contactPhone: '', addressLine1: '', addressLine2: '',
    city: '', region: '', postalCode: '', country: '', defaultAddress: false,
  }
}

function resetForm() {
  Object.assign(form, emptyAddress())
  editingId.value = null
}

function editAddress(address: CustomerAddress) {
  editingId.value = address.id
  Object.assign(form, {
    label: address.label,
    contactName: address.contactName,
    contactPhone: address.contactPhone,
    addressLine1: address.addressLine1,
    addressLine2: address.addressLine2 || '',
    city: address.city,
    region: address.region || '',
    postalCode: address.postalCode,
    country: address.country,
    defaultAddress: address.defaultAddress,
  })
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function loadAddresses() {
  addresses.value = await fetchCustomerAddresses()
}

async function saveAddress() {
  saving.value = true
  errorMessage.value = ''
  try {
    if (editingId.value) await updateCustomerAddress(editingId.value, form)
    else await createCustomerAddress(form)
    await loadAddresses()
    resetForm()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to save the address.'
  } finally {
    saving.value = false
  }
}

async function removeAddress(address: CustomerAddress) {
  if (!window.confirm(`Delete “${address.label}”? Existing orders keep their saved address snapshot.`)) return
  try {
    await deleteCustomerAddress(address.id)
    await loadAddresses()
    if (editingId.value === address.id) resetForm()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to delete the address.'
  }
}

onMounted(async () => {
  if (!customerSession.value) {
    await router.replace('/account/login?next=/account/addresses')
    return
  }
  form.contactName = customerSession.value.customer.displayName
  form.contactPhone = customerSession.value.customer.mobile || ''
  try {
    await loadAddresses()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to load addresses.'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <LegacySiteNav active="account" cta-href="/submit/order" cta-label="New order" />
  <main class="portal-page order-create-page">
    <div class="portal-heading">
      <div><p class="section-tag">Collector portal</p><h1>Return addresses</h1><p>Orders save an address snapshot, so later edits never change historical orders.</p></div>
      <router-link class="btn-secondary" to="/account/cards">My cards</router-link>
    </div>
    <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>
    <form class="portal-form form-section" @submit.prevent="saveAddress">
      <h2>{{ editingId ? 'Edit address' : 'Add address' }}</h2>
      <div class="form-grid">
        <label>Label<input v-model="form.label" required maxlength="64" /></label>
        <label>Contact name<input v-model="form.contactName" required maxlength="128" /></label>
        <label>Phone<input v-model="form.contactPhone" required maxlength="64" /></label>
        <label>Country / region<select v-if="countryOptions.length" v-model="form.country" required @change="countryChanged"><option v-for="option in countryOptions" :key="option.value" :value="option.value">{{ option.label }}</option><option value="">Choose country / region</option></select><input v-else v-model="form.country" required maxlength="128" /></label>
        <label class="form-wide">Address line 1<input v-model="form.addressLine1" required maxlength="255" /></label>
        <label class="form-wide">Address line 2<input v-model="form.addressLine2" maxlength="255" /></label>
        <label>Region / state<select v-if="knownCountry" v-model="form.region" :required="knownCountry" @change="regionChanged"><option value="">Choose region / state</option><option v-for="option in regionOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select><input v-else v-model="form.region" maxlength="128" /></label>
        <label>City<select v-if="knownRegion" v-model="form.city" required><option value="">Choose city</option><option v-for="option in cityOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select><input v-else v-model="form.city" required maxlength="128" /></label>
        <label>Postal code<input v-model="form.postalCode" required maxlength="64" /></label>
      </div>
      <label class="check-label"><input v-model="form.defaultAddress" type="checkbox" /> Use as the default return address</label>
      <div class="form-row form-actions"><button class="btn-primary form-submit" :disabled="saving">{{ saving ? 'Saving...' : 'Save address' }}</button><button v-if="editingId" type="button" class="btn-secondary" @click="resetForm">Cancel</button></div>
    </form>
    <section class="form-section address-list-section">
      <h2>Saved addresses</h2>
      <div v-if="loading" class="portal-empty">Loading addresses...</div>
      <p v-else-if="!addresses.length" class="portal-empty">No saved address yet.</p>
      <article v-for="address in addresses" :key="address.id" class="address-card">
        <div><strong>{{ address.label }} <span v-if="address.defaultAddress" class="status-pill">default</span></strong><p>{{ address.contactName }} · {{ address.contactPhone }}</p><p>{{ address.addressLine1 }} {{ address.addressLine2 }} · {{ address.city }} {{ address.region }} {{ address.postalCode }} · {{ address.country }}</p></div>
        <div class="form-row"><button type="button" class="btn-secondary" @click="editAddress(address)">Edit</button><button type="button" class="text-button" @click="removeAddress(address)">Delete</button></div>
      </article>
    </section>
  </main>
  <LegacySiteFooter />
</template>
