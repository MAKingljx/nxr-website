<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import { fetchPrivateOrderTracking, type PrivateOrderTracking } from '../lib/merchantBatches'
import { orderStatusLabel, orderDisplayStatus } from '../lib/orderProgress'
const props = defineProps<{ token: string }>()
const order = ref<PrivateOrderTracking | null>(null), error = ref(''), loading = ref(false), updatedAt = ref('')
let generation = 0, timer: ReturnType<typeof setInterval> | undefined
async function refresh() {
  const current = ++generation
  loading.value = true; error.value = ''
  try { const next = await fetchPrivateOrderTracking(props.token); if (generation !== current) return; order.value = next; updatedAt.value = new Date().toLocaleTimeString() }
  catch { if (generation === current) { order.value = null; error.value = 'This tracking link is unavailable or has been replaced. Please request a new link from your agent.' } }
  finally { if (generation === current) loading.value = false }
}
watch(() => props.token, () => { order.value = null; void refresh() }, { immediate: true })
onMounted(() => { timer = setInterval(() => { if (!document.hidden && !loading.value && order.value) void refresh() }, 30_000) })
onBeforeUnmount(() => { generation++; if (timer) clearInterval(timer) })
</script>
<template><LegacySiteNav /><main class="portal-page private-tracking"><div class="portal-heading"><div><p class="section-tag">Private order tracking</p><h1>Your grading progress</h1><p>For questions or changes, contact the agent who submitted your cards.</p></div><button class="btn-secondary" :disabled="loading" @click="refresh">{{ loading ? 'Refreshing…' : 'Refresh' }}</button></div><p v-if="error" class="form-error" role="alert">{{ error }}</p><template v-if="order"><section class="form-section"><h2>{{ order.orderNo }}</h2><p>{{ order.clientReference }} · {{ order.totalCardCount ?? order.cardCount }} cards</p><strong class="status-pill">{{ orderStatusLabel(orderDisplayStatus(order.statusCode, order.admissionStatus)) }}</strong><p class="muted-copy">Updated {{ updatedAt }}</p></section><section class="form-section"><h2>Progress history</h2><ol class="tracking-timeline"><li v-for="(event, index) in order.timeline" :key="index"><strong>{{ event.title }}</strong><time>{{ new Date(event.createdAt).toLocaleString() }}</time></li><li v-if="!order.timeline?.length">The application has been received. Check back for updates.</li></ol></section><section v-if="order.shipments?.length" class="form-section"><h2>Parcel progress</h2><div v-for="(parcel, index) in order.shipments" :key="index" class="detail-row"><div><strong>{{ parcel.directionCode === 'inbound' ? 'Agent to NXR' : 'NXR to agent' }}</strong><span>{{ parcel.carrierName }} · {{ parcel.trackingNumber }}</span></div><span>{{ orderStatusLabel(parcel.statusCode) }}</span></div></section></template><p v-else-if="loading">Loading your order…</p></main><LegacySiteFooter /></template>
<style scoped>.private-tracking{max-width:900px}.tracking-timeline{list-style:none;padding:0}.tracking-timeline li{display:flex;justify-content:space-between;gap:20px;padding:18px 0;border-bottom:1px solid var(--border,#ddd)}.tracking-timeline time{font-size:13px;color:var(--text-muted,#777)}@media(max-width:600px){.tracking-timeline li{flex-direction:column;gap:6px}}</style>
