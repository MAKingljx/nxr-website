<script setup lang="ts">
import { computed } from 'vue'
import { safeAmount, safeCount, safeImageUrl } from './safety'

export interface PhoenixLiveProduct {
  id: string | number
  title: string
  image?: string
  price: number
  originalPrice?: number
  stock?: number
  sales?: number
  featured?: boolean
  disabled?: boolean
}

const props = withDefaults(defineProps<{
  products: PhoenixLiveProduct[]
  selectedId?: string | number | null
  title?: string
  currency?: string
  locale?: string
  loading?: boolean
  disabled?: boolean
  emptyText?: string
}>(), {
  selectedId: null,
  title: '直播商品',
  currency: 'CNY',
  locale: 'zh-CN',
  loading: false,
  disabled: false,
  emptyText: '暂无直播商品',
})

const emit = defineEmits<{
  'update:selectedId': [id: string | number]
  select: [product: PhoenixLiveProduct]
  'request-add': [product: PhoenixLiveProduct]
  'request-feature': [product: PhoenixLiveProduct, featured: boolean]
}>()

const count = computed(() => safeCount(props.products.length, 10_000))

function formatPrice(value: number) {
  const amount = safeAmount(value)
  try {
    return new Intl.NumberFormat(props.locale, { style: 'currency', currency: props.currency }).format(amount)
  } catch {
    return `${props.currency} ${amount.toFixed(2)}`
  }
}

function select(product: PhoenixLiveProduct) {
  if (props.disabled || product.disabled) return
  emit('update:selectedId', product.id)
  emit('select', product)
}

function requestAdd(product: PhoenixLiveProduct) {
  if (props.disabled || product.disabled || safeCount(product.stock ?? 0) === 0) return
  emit('request-add', product)
}

function requestFeature(product: PhoenixLiveProduct) {
  if (!props.disabled && !product.disabled) emit('request-feature', product, !product.featured)
}
</script>

<template>
  <section class="px-live-product-shelf" :aria-label="title" :aria-busy="loading">
    <header><h3>{{ title }}</h3><strong>{{ count }} 件</strong></header>
    <p v-if="loading" class="px-live-state" role="status">商品加载中</p>
    <p v-else-if="!products.length" class="px-live-state" role="status">{{ emptyText }}</p>
    <ul v-else>
      <li v-for="product in products" :key="product.id" :class="{ 'is-selected': selectedId === product.id }">
        <button class="px-live-product-shelf__select" type="button" :disabled="disabled || product.disabled" :aria-pressed="selectedId === product.id" @click="select(product)">
          <span class="px-live-product-shelf__image">
            <img v-if="safeImageUrl(product.image)" :src="safeImageUrl(product.image)" :alt="product.title" loading="lazy">
            <span v-else aria-hidden="true">{{ product.title.trim().slice(0, 1) || '商' }}</span>
          </span>
          <span class="px-live-product-shelf__content">
            <strong>{{ product.title }}</strong>
            <span>{{ formatPrice(product.price) }}</span>
            <del v-if="safeAmount(product.originalPrice ?? 0) > safeAmount(product.price)">{{ formatPrice(product.originalPrice ?? 0) }}</del>
            <small>库存 {{ safeCount(product.stock ?? 0) }} · 已售 {{ safeCount(product.sales ?? 0) }}</small>
          </span>
        </button>
        <div class="px-live-product-shelf__actions">
          <button type="button" :disabled="disabled || product.disabled" @click="requestFeature(product)">{{ product.featured ? '取消讲解' : '设为讲解' }}</button>
          <button type="button" :disabled="disabled || product.disabled || safeCount(product.stock ?? 0) === 0" @click="requestAdd(product)">加入购物车</button>
        </div>
      </li>
    </ul>
  </section>
</template>
