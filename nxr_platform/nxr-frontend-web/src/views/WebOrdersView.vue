<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import { customerSession, fetchCustomerOrders, type GradingOrderList } from '../lib/customer'

const router = useRouter()
const data = ref<GradingOrderList | null>(null)
const loading = ref(true)
const errorMessage = ref('')

function dateLabel(value: string) {
  return new Date(value).toLocaleString()
}

async function loadOrders() {
  if (!customerSession.value) {
    await router.replace('/account/login?next=/account/orders')
    return
  }
  loading.value = true
  try {
    data.value = await fetchCustomerOrders()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to load your orders.'
  } finally {
    loading.value = false
  }
}

onMounted(() => void loadOrders())
</script>

<template>
  <LegacySiteNav active="account" cta-href="/submit/order" cta-label="New order" />
  <main class="portal-page">
    <div class="portal-heading"><div><p class="section-tag">Collector portal</p><h1>My Grading Orders</h1><p>Payment, receiving, grading and return-shipment status in one place.</p></div><div class="form-row form-actions"><router-link class="btn-secondary" to="/account/addresses">Addresses</router-link><router-link v-if="customerSession?.customer.accountTypeCode === 'merchant'" class="btn-secondary" to="/account/merchant-orders">Bulk orders</router-link><router-link class="btn-secondary" to="/account/cards">My cards</router-link></div></div>
    <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>
    <div v-if="loading" class="portal-empty">Loading orders...</div>
    <div v-else-if="!data?.items.length" class="portal-empty">You do not have a grading order yet. Start one when your cards are ready.</div>
    <div v-else class="order-list">
      <router-link v-for="order in data.items" :key="order.orderNo" class="order-row" :to="`/account/orders/${encodeURIComponent(order.orderNo)}`">
        <div><strong>{{ order.orderNo }}</strong><span>{{ order.totalCardCount }} card{{ order.totalCardCount === 1 ? '' : 's' }} · {{ order.serviceLevelCode }}</span></div>
        <div><span class="status-pill">{{ order.statusCode.replaceAll('_', ' ') }}</span><strong>{{ order.currencyCode }} {{ Number(order.totalAmount).toFixed(2) }}</strong><span>{{ dateLabel(order.createdAt) }}</span></div>
      </router-link>
    </div>
  </main>
  <LegacySiteFooter />
</template>
