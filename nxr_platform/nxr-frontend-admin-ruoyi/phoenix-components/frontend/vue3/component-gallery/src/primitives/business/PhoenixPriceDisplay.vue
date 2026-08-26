<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  value?: number | null
  originalPrice?: number | null
  currency?: string
  locale?: string
  suffix?: string
  size?: 'small' | 'medium' | 'large'
  emptyText?: string
}>(), {
  value: null, originalPrice: null, currency: 'CNY', locale: 'zh-CN', suffix: '', size: 'medium', emptyText: '暂无价格',
})

function format(value: number) {
  try {
    return new Intl.NumberFormat(props.locale, { style: 'currency', currency: props.currency, minimumFractionDigits: 2 }).format(value)
  } catch {
    return `${props.currency} ${value.toFixed(2)}`
  }
}

const validValue = computed(() => typeof props.value === 'number' && Number.isFinite(props.value))
const current = computed(() => validValue.value ? format(props.value as number) : props.emptyText)
const original = computed(() => typeof props.originalPrice === 'number' && Number.isFinite(props.originalPrice) && validValue.value && props.originalPrice > (props.value as number)
  ? format(props.originalPrice)
  : '')
</script>

<template>
  <span class="px-price-display" :class="`px-price-display--${size}`" :aria-label="validValue ? `价格 ${current}` : emptyText">
    <strong>{{ current }}</strong>
    <del v-if="original">{{ original }}</del>
    <span v-if="suffix && validValue">{{ suffix }}</span>
  </span>
</template>
