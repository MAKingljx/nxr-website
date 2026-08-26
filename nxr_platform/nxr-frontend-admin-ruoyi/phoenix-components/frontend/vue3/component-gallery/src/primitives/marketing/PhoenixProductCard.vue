<script setup lang="ts">
import { computed } from 'vue'
import type { PhoenixMarketingAppearance } from './PhoenixLuckyDraw.vue'

const props = withDefaults(defineProps<{
  title: string
  image?: string
  price: number
  originalPrice?: number
  badge?: string
  description?: string
  rating?: number
  sales?: number
  inventory?: number
  layout?: 'vertical' | 'horizontal' | 'compact'
  appearance?: PhoenixMarketingAppearance
  currency?: string
  favorited?: boolean
  disabled?: boolean
  loading?: boolean
  actionLabel?: string
}>(), {
  image: '',
  originalPrice: 0,
  badge: '',
  description: '',
  rating: 0,
  sales: 0,
  inventory: 0,
  layout: 'vertical',
  appearance: 'modern',
  currency: 'CNY',
  favorited: false,
  disabled: false,
  loading: false,
  actionLabel: '加入购物车',
})

const emit = defineEmits<{
  select: []
  'add-cart': []
  'update:favorited': [value: boolean]
  favorite: [value: boolean]
}>()

function finite(value: number) {
  return Number.isFinite(value) ? Math.max(0, value) : 0
}

const normalizedPrice = computed(() => finite(props.price))
const normalizedOriginalPrice = computed(() => finite(props.originalPrice))
const normalizedRating = computed(() => Math.min(5, finite(props.rating)))
const normalizedSales = computed(() => Math.trunc(finite(props.sales)))
const normalizedInventory = computed(() => Math.trunc(finite(props.inventory)))
const unavailable = computed(() => props.disabled || props.loading || normalizedInventory.value <= 0)

const safeImage = computed(() => {
  const normalized = props.image.trim()
  return /^(https?:\/\/|\/|\.\/|\.\.\/|blob:|data:image\/(?!svg\+xml)[a-z0-9.+-]+;base64,)/i.test(normalized)
    ? normalized
    : ''
})

function money(value: number) {
  try {
    return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: props.currency }).format(value)
  } catch {
    return `${props.currency} ${value.toFixed(2)}`
  }
}

function select() {
  if (!props.disabled && !props.loading) emit('select')
}

function onKeydown(event: KeyboardEvent) {
  if (!['Enter', ' '].includes(event.key) || props.disabled || props.loading) return
  event.preventDefault()
  select()
}

function favorite(event: Event) {
  event.stopPropagation()
  if (props.disabled || props.loading) return
  emit('update:favorited', !props.favorited)
  emit('favorite', !props.favorited)
}

function addCart(event: Event) {
  event.stopPropagation()
  if (!unavailable.value) emit('add-cart')
}
</script>

<template>
  <article
    class="px-product-card"
    :class="[`px-product-card--${layout}`, { 'is-disabled': disabled, 'is-loading': loading }]"
    :data-appearance="appearance"
    :tabindex="disabled ? -1 : 0"
    :aria-label="title"
    :aria-busy="loading"
    :aria-disabled="disabled || undefined"
    @click="select"
    @keydown="onKeydown"
  >
    <div class="px-product-card__media">
      <img v-if="safeImage" :src="safeImage" :alt="title" loading="lazy">
      <span v-else aria-hidden="true">{{ title.slice(0, 1) }}</span>
      <strong v-if="badge">{{ badge }}</strong>
      <button type="button" :aria-label="favorited ? '取消收藏' : '收藏商品'" :aria-pressed="favorited" :disabled="disabled || loading" @click="favorite">{{ favorited ? '♥' : '♡' }}</button>
    </div>
    <div class="px-product-card__body">
      <h3>{{ title }}</h3>
      <p v-if="description">{{ description }}</p>
      <div class="px-product-card__price"><strong>{{ money(normalizedPrice) }}</strong><del v-if="normalizedOriginalPrice > normalizedPrice">{{ money(normalizedOriginalPrice) }}</del></div>
      <div class="px-product-card__meta"><span>★ {{ normalizedRating.toFixed(1) }}</span><span>已售 {{ normalizedSales }}</span><span>{{ normalizedInventory > 0 ? `库存 ${normalizedInventory}` : '已售罄' }}</span></div>
      <button type="button" :disabled="unavailable" @click="addCart">{{ loading ? '加载中' : actionLabel }}</button>
    </div>
  </article>
</template>
