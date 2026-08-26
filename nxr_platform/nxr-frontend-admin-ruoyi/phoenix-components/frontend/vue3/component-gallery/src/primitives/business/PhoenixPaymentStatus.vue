<script setup lang="ts">
import { computed } from 'vue'

type PaymentState = 'pending' | 'paid' | 'failed' | 'refunded' | 'cancelled' | 'unknown'

const props = withDefaults(defineProps<{
  status?: PaymentState
  amount?: number | null
  currency?: string
  locale?: string
  reference?: string
  actionLabel?: string
  actionDisabled?: boolean
}>(), {
  status: 'unknown', amount: null, currency: 'CNY', locale: 'zh-CN', reference: '', actionLabel: '', actionDisabled: false,
})

const emit = defineEmits<{ action: [status: PaymentState] }>()
const labels: Record<PaymentState, string> = {
  pending: '待支付', paid: '已支付', failed: '支付失败', refunded: '已退款', cancelled: '已取消', unknown: '状态未知',
}
const icons: Record<PaymentState, string> = {
  pending: '…', paid: '✓', failed: '!', refunded: '↩', cancelled: '×', unknown: '?',
}
const label = computed(() => labels[props.status] || labels.unknown)
const icon = computed(() => icons[props.status] || icons.unknown)
const formattedAmount = computed(() => {
  if (typeof props.amount !== 'number' || !Number.isFinite(props.amount)) return ''
  try {
    return new Intl.NumberFormat(props.locale, { style: 'currency', currency: props.currency }).format(props.amount)
  } catch {
    return `${props.currency} ${props.amount.toFixed(2)}`
  }
})
</script>

<template>
  <section class="px-payment-status" :class="`is-${status}`" :aria-label="`支付状态：${label}`" role="status">
    <span class="px-payment-status__icon" aria-hidden="true">{{ icon }}</span>
    <div><h3>{{ label }}</h3><strong v-if="formattedAmount">{{ formattedAmount }}</strong><span v-if="reference">{{ reference }}</span></div>
    <button v-if="actionLabel" type="button" :disabled="actionDisabled" @click="emit('action', status)">{{ actionLabel }}</button>
  </section>
</template>
