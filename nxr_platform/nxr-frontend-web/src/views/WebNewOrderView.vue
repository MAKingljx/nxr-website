<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import { createGradingOrder, customerSession } from '../lib/customer'

const router = useRouter()
const errorMessage = ref('')
const submitting = ref(false)
const form = reactive({
  serviceLevel: 'standard',
  contactName: '',
  contactPhone: '',
  returnAddressLine1: '',
  returnAddressLine2: '',
  returnCity: '',
  returnRegion: '',
  returnPostalCode: '',
  returnCountry: '',
  customerNote: '',
  items: [newCardItem()],
})

function newCardItem() {
  return { cardName: '', brandName: '', setName: '', cardNumber: '', languageCode: 'EN', declaredValue: '', itemNote: '' }
}

function addCard() {
  if (form.items.length < 30) form.items.push(newCardItem())
}

function removeCard(index: number) {
  if (form.items.length > 1) form.items.splice(index, 1)
}

async function submitOrder() {
  errorMessage.value = ''
  submitting.value = true
  try {
    const order = await createGradingOrder({
      ...form,
      items: form.items.map((item) => ({
        ...item,
        declaredValue: item.declaredValue === '' ? null : Number(item.declaredValue),
      })),
    })
    await router.push(`/account/orders/${encodeURIComponent(order.orderNo)}`)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to create the grading order.'
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  if (!customerSession.value) await router.replace('/account/login?next=/submit/order')
  else {
    form.contactName = customerSession.value.customer.displayName
    form.contactPhone = customerSession.value.customer.mobile || ''
  }
})
</script>

<template>
  <LegacySiteNav active="submit" />
  <main class="portal-page order-create-page">
    <div class="portal-heading"><div><p class="section-tag">New grading order</p><h1>Send cards to NXR</h1><p>Create the intake, submit payment confirmation, then add your inbound tracking when payment is approved.</p></div><router-link class="btn-secondary" to="/account/orders">My orders</router-link></div>
    <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>
    <form class="portal-form order-form" @submit.prevent="submitOrder">
      <section class="form-section"><h2>Service</h2><div class="tier-select"><label><input v-model="form.serviceLevel" type="radio" value="standard" />Standard <span>USD 20 / card</span></label><label><input v-model="form.serviceLevel" type="radio" value="express" />Express <span>USD 35 / card</span></label><label><input v-model="form.serviceLevel" type="radio" value="premium" />Premium <span>USD 50 / card</span></label></div></section>
      <section class="form-section"><h2>Cards</h2><div v-for="(item, index) in form.items" :key="index" class="order-card-form"><div class="form-row form-row-title"><strong>Card {{ index + 1 }}</strong><button v-if="form.items.length > 1" type="button" class="text-button" @click="removeCard(index)">Remove</button></div><div class="form-grid"><label>Card name<input v-model="item.cardName" required maxlength="255" /></label><label>Brand<input v-model="item.brandName" maxlength="128" /></label><label>Set<input v-model="item.setName" maxlength="255" /></label><label>Card number<input v-model="item.cardNumber" maxlength="128" /></label><label>Language<input v-model="item.languageCode" maxlength="32" /></label><label>Declared value (USD)<input v-model="item.declaredValue" type="number" min="0" max="1000000" step="0.01" /></label></div><label>Item note<textarea v-model="item.itemNote" maxlength="1000" /></label></div><button v-if="form.items.length < 30" type="button" class="btn-secondary" @click="addCard">Add another card</button></section>
      <section class="form-section"><h2>Return details</h2><div class="form-grid"><label>Contact name<input v-model="form.contactName" required maxlength="128" /></label><label>Phone<input v-model="form.contactPhone" required maxlength="64" /></label><label class="form-wide">Address line 1<input v-model="form.returnAddressLine1" required maxlength="255" /></label><label class="form-wide">Address line 2<input v-model="form.returnAddressLine2" maxlength="255" /></label><label>City<input v-model="form.returnCity" required maxlength="128" /></label><label>Region / state<input v-model="form.returnRegion" maxlength="128" /></label><label>Postal code<input v-model="form.returnPostalCode" required maxlength="64" /></label><label>Country<input v-model="form.returnCountry" required maxlength="128" /></label></div><label>Order note<textarea v-model="form.customerNote" maxlength="2000" /></label></section>
      <button class="btn-primary form-submit" type="submit" :disabled="submitting">{{ submitting ? 'Creating order...' : 'Create order and view payment' }}</button>
    </form>
  </main>
  <LegacySiteFooter />
</template>
