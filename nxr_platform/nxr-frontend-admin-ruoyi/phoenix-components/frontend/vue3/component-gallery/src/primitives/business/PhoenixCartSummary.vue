<script setup lang="ts">
import { computed } from 'vue'

export interface PhoenixCartItem {
  id: string | number
  title: string
  quantity: number
  unitPrice: number
  disabled?: boolean
}

const props = withDefaults(defineProps<{
  items: PhoenixCartItem[]
  shipping?: number
  discount?: number
  currency?: string
  locale?: string
  title?: string
  checkoutText?: string
  checkoutDisabled?: boolean
  emptyText?: string
}>(), {
  shipping: 0, discount: 0, currency: 'CNY', locale: 'zh-CN', title: '购物车', checkoutText: '提交订单',
  checkoutDisabled: false, emptyText: '购物车为空',
})

const emit = defineEmits<{
  checkout: [{ subtotal: number, total: number }]
  remove: [item: PhoenixCartItem]
  'change-quantity': [item: PhoenixCartItem, quantity: number]
}>()

const amount = (value: number) => Number.isFinite(value) ? Math.max(0, value) : 0
const quantity = (value: number) => Number.isFinite(value) ? Math.max(0, Math.trunc(value)) : 0
const subtotal = computed(() => props.items.reduce((sum, item) => sum + amount(item.unitPrice) * quantity(item.quantity), 0))
const total = computed(() => Math.max(0, subtotal.value + amount(props.shipping) - amount(props.discount)))

function format(value: number) {
  try {
    return new Intl.NumberFormat(props.locale, { style: 'currency', currency: props.currency }).format(value)
  } catch {
    return `${props.currency} ${value.toFixed(2)}`
  }
}
</script>

<template>
  <section class="px-cart-summary" :aria-label="title">
    <header><h3>{{ title }}</h3><strong>{{ items.length }} 件</strong></header>
    <p v-if="!items.length" class="px-business-empty" role="status">{{ emptyText }}</p>
    <ul v-else>
      <li v-for="item in items" :key="item.id">
        <div><strong>{{ item.title }}</strong><span>{{ format(amount(item.unitPrice)) }} × {{ quantity(item.quantity) }}</span></div>
        <div class="px-cart-summary__actions">
          <strong>{{ format(amount(item.unitPrice) * quantity(item.quantity)) }}</strong>
          <button type="button" :aria-label="`减少 ${item.title} 数量`" :disabled="item.disabled || quantity(item.quantity) <= 0" @click="emit('change-quantity', item, Math.max(0, quantity(item.quantity) - 1))">−</button>
          <button type="button" :aria-label="`增加 ${item.title} 数量`" :disabled="item.disabled" @click="emit('change-quantity', item, quantity(item.quantity) + 1)">+</button>
          <button type="button" :aria-label="`移除 ${item.title}`" :disabled="item.disabled" @click="emit('remove', item)">移除</button>
        </div>
      </li>
    </ul>
    <dl>
      <div><dt>商品金额</dt><dd>{{ format(subtotal) }}</dd></div>
      <div v-if="amount(shipping)"><dt>配送费</dt><dd>{{ format(amount(shipping)) }}</dd></div>
      <div v-if="amount(discount)"><dt>优惠</dt><dd>−{{ format(amount(discount)) }}</dd></div>
      <div class="px-cart-summary__total"><dt>合计</dt><dd>{{ format(total) }}</dd></div>
    </dl>
    <button class="px-business-primary" type="button" :disabled="checkoutDisabled || !items.length" @click="emit('checkout', { subtotal, total })">{{ checkoutText }}</button>
  </section>
</template>
