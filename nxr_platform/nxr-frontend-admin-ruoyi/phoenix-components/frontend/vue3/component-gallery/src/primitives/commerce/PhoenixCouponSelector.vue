<script setup lang="ts">
import { computed, useId } from 'vue'
import { clampNumber, formatCurrency } from './utils'

export interface PhoenixCoupon {
  id: string | number
  title: string
  discount: number
  minSpend?: number
  expiresAt?: string
  disabled?: boolean
}
const props = withDefaults(defineProps<{
  modelValue?: string | number | null
  coupons: PhoenixCoupon[]
  orderAmount?: number
  currency?: string
  locale?: string
  disabled?: boolean
  allowNone?: boolean
}>(), { modelValue: null, orderAmount: 0, currency: 'CNY', locale: 'zh-CN', disabled: false, allowNone: true })
const emit = defineEmits<{
  'update:modelValue': [id: string | number | null]
  change: [coupon: PhoenixCoupon | null]
}>()
const name = `phoenix-coupon-${useId()}`
const amount = computed(() => clampNumber(props.orderAmount, 0, Number.MAX_SAFE_INTEGER))
function threshold(coupon: PhoenixCoupon) { return clampNumber(coupon.minSpend ?? 0, 0, Number.MAX_SAFE_INTEGER) }
function discount(coupon: PhoenixCoupon) { return clampNumber(coupon.discount, 0, Number.MAX_SAFE_INTEGER) }
function unavailable(coupon: PhoenixCoupon) { return props.disabled || coupon.disabled || amount.value < threshold(coupon) }
function choose(coupon: PhoenixCoupon | null) {
  if (coupon && unavailable(coupon)) return
  emit('update:modelValue', coupon?.id ?? null); emit('change', coupon)
}
</script>

<template>
  <fieldset class="px-coupon-selector" :disabled="disabled">
    <legend>选择优惠券</legend>
    <label v-if="allowNone" :class="{ 'is-selected': modelValue == null }"><input type="radio" :name="name" :checked="modelValue == null" @change="choose(null)"><span><strong>不使用优惠券</strong><small>按原价结算</small></span></label>
    <label v-for="coupon in coupons" :key="coupon.id" :class="{ 'is-selected': modelValue === coupon.id, 'is-disabled': unavailable(coupon) }">
      <input type="radio" :name="name" :value="coupon.id" :checked="modelValue === coupon.id" :disabled="unavailable(coupon)" @change="choose(coupon)">
      <span class="px-coupon-selector__amount">{{ formatCurrency(discount(coupon), currency, locale) }}</span>
      <span><strong>{{ coupon.title }}</strong><small>满 {{ formatCurrency(threshold(coupon), currency, locale) }} 可用</small><small v-if="coupon.expiresAt">有效期至 {{ coupon.expiresAt }}</small></span>
    </label>
    <p v-if="!coupons.length" class="px-commerce-empty" role="status">暂无可用优惠券</p>
  </fieldset>
</template>
