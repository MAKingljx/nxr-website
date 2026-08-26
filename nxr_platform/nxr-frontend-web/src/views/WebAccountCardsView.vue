<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import { customerSession, fetchCustomerCards, type CustomerCard } from '../lib/customer'

const router = useRouter()
const cards = ref<CustomerCard[]>([])
const loading = ref(true)
const errorMessage = ref('')
const placeholderImage = `${import.meta.env.BASE_URL}static/placeholder.png`

function productSummary(card: CustomerCard) {
  if (card.productType === 'merch_product' || card.productType === 'label_product') return 'Merch Product'
  if (card.productType === 'vintage_product') return card.vintageClassification || 'Vintage Card'
  if (card.finalGradeValue === null) return 'Graded Card'
  return `${Number(card.finalGradeValue).toFixed(1)} ${card.finalGradeLabel || ''}`.trim()
}

async function loadCards() {
  if (!customerSession.value) {
    await router.replace('/account/login?next=/account/cards')
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    cards.value = await fetchCustomerCards()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to load your cards.'
  } finally {
    loading.value = false
  }
}

onMounted(() => void loadCards())
</script>

<template>
  <LegacySiteNav active="account" cta-href="/submit/order" cta-label="New order" />
  <main class="portal-page">
    <div class="portal-heading">
      <div><p class="section-tag">Collector portal</p><h1>My Cards</h1><p>Verified certificates currently bound to your collector account.</p></div>
      <router-link class="btn-secondary" to="/account/orders">View orders</router-link>
    </div>
    <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>
    <div v-if="loading" class="portal-empty">Loading your cards...</div>
    <div v-else-if="!cards.length" class="portal-empty">No cards are bound yet. Open any verified certificate to add it to your collection.</div>
    <div v-else class="collection-grid">
      <router-link v-for="card in cards" :key="card.certId" class="collection-card" :to="`/card/${encodeURIComponent(card.certId)}`">
        <img :src="card.frontImageUrl || placeholderImage" :alt="card.cardName" />
        <div><span class="collection-cert">{{ card.certId }}</span><h2>{{ card.cardName }}</h2><p>{{ card.brandName }} {{ card.yearLabel }}</p><p v-if="card.merchDescription">{{ card.merchDescription }}</p><strong>{{ productSummary(card) }}</strong></div>
      </router-link>
    </div>
  </main>
  <LegacySiteFooter />
</template>
