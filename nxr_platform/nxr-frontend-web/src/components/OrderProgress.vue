<script setup lang="ts">
import { computed } from 'vue'
import type { GradingOrder, TrackingEvent } from '../lib/customer'
import { orderProgress, orderStatusLabel } from '../lib/orderProgress'
const props = defineProps<{ order: GradingOrder; trackingEvents: TrackingEvent[]; refreshing: boolean; updatedAt: string }>()
defineEmits<{ refresh: [] }>()
const steps = computed(() => orderProgress(props.order))
const directions = [{ code: 'inbound', label: 'Shipment to NXR', empty: 'Add your tracking number after sending the parcel.' }, { code: 'outbound', label: 'Return shipment', empty: 'Carrier and tracking will appear when your cards are dispatched.' }]
const dateLabel = (value: string) => new Date(value).toLocaleString()
</script>

<template>
  <section class="form-section order-progress" aria-label="Order progress">
    <div class="progress-heading"><div><h2>Order progress</h2><p class="muted-copy">{{ orderStatusLabel(order.statusCode) }}<template v-if="updatedAt"> · Updated {{ dateLabel(updatedAt) }}</template></p></div><button type="button" class="btn-secondary no-print" :disabled="refreshing" @click="$emit('refresh')">{{ refreshing ? 'Refreshing…' : 'Refresh' }}</button></div>
    <p v-if="order.statusCode === 'cancelled'" class="form-error">This order has been cancelled.</p>
    <p v-if="order.statusCode === 'payment_exception'" class="form-error">This payment requires review. Please contact NXR support before sending your cards.</p>
    <ol class="progress-steps">
      <li v-for="(step, index) in steps" :key="step.label" :class="{ current: step.active, done: step.completed }" :aria-current="step.active ? 'step' : undefined">
        <span class="milestone-number">{{ step.completed ? '✓' : index + 1 }}</span><div><strong>{{ step.label }}</strong><time v-if="step.reachedAt">{{ dateLabel(step.reachedAt) }}</time><span v-else>{{ step.active ? 'In progress' : step.completed ? 'Completed' : 'Pending' }}</span></div>
      </li>
    </ol>
    <div class="shipment-grid">
      <article v-for="direction in directions" :key="direction.code" class="shipment-card">
        <h3>{{ direction.label }}</h3>
        <p v-if="!order.shipments.some(item => item.directionCode === direction.code)" class="muted-copy">{{ direction.empty }}</p>
        <div v-for="shipment in order.shipments.filter(item => item.directionCode === direction.code)" :key="shipment.id" class="shipment-detail">
          <div class="progress-heading"><strong>{{ shipment.carrierName }}</strong><span class="status-pill">{{ orderStatusLabel(shipment.statusCode) }}</span></div>
          <p class="tracking-number">{{ shipment.trackingNumber }}</p>
          <p class="muted-copy">Shipped {{ dateLabel(shipment.shippedAt) }}<br v-if="shipment.deliveredAt" /><template v-if="shipment.deliveredAt">Delivered {{ dateLabel(shipment.deliveredAt) }}</template></p>
          <ol v-if="trackingEvents.some(event => event.shipmentId === shipment.id)" class="order-timeline tracking-timeline">
            <li v-for="event in trackingEvents.filter(event => event.shipmentId === shipment.id)" :key="event.id"><span></span><div><strong>{{ event.eventTitle }}</strong><p v-if="event.locationLabel || event.eventDetail">{{ [event.locationLabel, event.eventDetail].filter(Boolean).join(' · ') }}</p><time>{{ dateLabel(event.eventTime) }}</time></div></li>
          </ol>
          <p v-else class="muted-copy">Further tracking updates will appear here.</p>
        </div>
      </article>
    </div>
    <details class="progress-history"><summary>Full order history ({{ order.timeline.length }})</summary><ol class="order-timeline"><li v-for="event in order.timeline" :key="event.id"><span></span><div><strong>{{ event.title }}</strong><p v-if="event.detail">{{ event.detail }}</p><time>{{ dateLabel(event.createdAt) }}</time></div></li></ol></details>
    <p class="muted-copy no-print">Updates automatically every 20 seconds while this page is open.</p>
  </section>
</template>

<style scoped>
.progress-heading {display:flex;justify-content:space-between;gap:16px;align-items:center}
.progress-heading h2 {margin:0}
.progress-steps {display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:22px 12px;list-style:none;padding:24px 0;margin:0}
.progress-steps li {display:flex;gap:10px;color:var(--text2);align-items:flex-start}
.milestone-number {display:grid;place-items:center;flex:0 0 28px;height:28px;border-radius:50%;background:var(--bg4);color:var(--text2);font-size:13px}
.progress-steps strong,.progress-steps time,.progress-steps div>span {display:block;font-size:12px;line-height:1.5}
.progress-steps strong {font-size:13px}.progress-steps .current {color:var(--gold)}.progress-steps .done {color:#b7d6c6}
.current .milestone-number {background:var(--gold);color:#191919;box-shadow:0 0 0 4px #f5c84220}.done .milestone-number {background:#264b3c;color:#b7f2cf}
.shipment-grid {display:grid;grid-template-columns:1fr 1fr;gap:20px}.shipment-card {border:1px solid var(--border2);border-radius:10px;padding:20px;min-width:0}
.shipment-card h3 {margin:0 0 16px;font-size:17px}.shipment-detail+.shipment-detail {border-top:1px solid var(--border2);margin-top:20px;padding-top:20px}
.tracking-number {font-family:monospace;overflow-wrap:anywhere;font-size:16px;user-select:all}.tracking-timeline {margin-top:22px}.progress-history {margin:22px 0}.progress-history summary {cursor:pointer;font-weight:600}
@media(max-width:760px){.progress-steps {grid-template-columns:1fr 1fr}.shipment-grid {grid-template-columns:1fr}.progress-heading {flex-wrap:wrap}}
</style>
