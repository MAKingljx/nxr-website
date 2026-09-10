<script setup lang="ts">
import { formatMoney, orderStatusLabel, orderDisplayStatus } from '../lib/orderProgress'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import { customerSession, fetchCustomerOrders, type GradingOrderList } from '../lib/customer'

const router = useRouter()
const data = ref<GradingOrderList | null>(null)
const loading = ref(true)
const errorMessage = ref('')
const currentPage = ref(1)
const refreshing = ref(false)
let timer: number | undefined
let generation = 0

function dateLabel(value: string) {
  return new Date(value).toLocaleString()
}

async function loadOrders(page = currentPage.value, showLoader = true) {
  if (!customerSession.value) {
    await router.replace('/account/login?next=/account/orders')
    return
  }
  const request = ++generation
  if (showLoader) loading.value = true
  refreshing.value = true
  try {
    const result = await fetchCustomerOrders(page)
    if (request !== generation) return
    data.value = result
    currentPage.value = page
    errorMessage.value = ''
  } catch (error) {
    if (request === generation) errorMessage.value = error instanceof Error ? error.message : 'Unable to load your orders.'
  } finally {
    if (request === generation) { loading.value = false; refreshing.value = false }
  }
}

onMounted(() => {
  void loadOrders()
  timer = window.setInterval(() => { if (!document.hidden && !refreshing.value) void loadOrders(currentPage.value, false) }, 20000)
})
onBeforeUnmount(() => { ++generation; if (timer) window.clearInterval(timer) })
</script>

<template>
  <LegacySiteNav active="account" cta-href="/submit/order" cta-label="New order" />
  <main class="portal-page">
    <div class="portal-heading"><div><p class="section-tag">Collector portal</p><h1>My Grading Orders</h1><p>Payment, receiving, grading and return-shipment status in one place.</p></div><div class="form-row form-actions"><router-link class="btn-secondary" to="/account/addresses">Addresses</router-link><router-link v-if="customerSession?.customer.accountTypeCode === 'merchant'" class="btn-secondary" to="/account/company">Company & balance</router-link><router-link v-if="customerSession?.customer.accountTypeCode === 'merchant'" class="btn-secondary" to="/account/merchant-orders">Bulk orders</router-link><router-link class="btn-secondary" to="/account/cards">My cards</router-link></div></div>
    <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>
    <div v-if="loading" class="portal-empty">Loading orders...</div>
    <div v-else-if="!data?.items.length" class="portal-empty">You do not have a grading order yet. Start one when your cards are ready.</div>
    <div v-else class="order-list">
      <router-link v-for="order in data.items" :key="order.orderNo" class="order-row" :to="`/account/orders/${encodeURIComponent(order.orderNo)}`">
        <div><strong>{{ order.orderNo }}</strong><span>{{ order.totalCardCount }} card{{ order.totalCardCount === 1 ? '' : 's' }} · {{ order.serviceLevelCode }}</span></div>
        <div><span class="status-pill">{{ orderStatusLabel(orderDisplayStatus(order.statusCode, order.admissionStatus)) }}</span><strong>{{ formatMoney(order.totalAmount, order.currencyCode) }}</strong><span>{{ dateLabel(order.createdAt) }}</span></div>
      </router-link>
    </div>
    <div v-if="data && data.total > data.pageSize" class="form-row form-actions" style="margin-top:24px"><button class="btn-secondary" :disabled="refreshing || currentPage === 1" @click="loadOrders(currentPage - 1)">Previous</button><span>Page {{ currentPage }} of {{ Math.ceil(data.total / data.pageSize) }}</span><button class="btn-secondary" :disabled="refreshing || currentPage * data.pageSize >= data.total" @click="loadOrders(currentPage + 1)">Next</button></div>
  </main>
  <LegacySiteFooter />
</template>
