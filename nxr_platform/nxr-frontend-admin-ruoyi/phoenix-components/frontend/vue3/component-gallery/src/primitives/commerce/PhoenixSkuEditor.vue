<script setup lang="ts">
import { computed } from 'vue'
import { clampInteger, clampNumber, formatCurrency, safeImageUrl } from './utils'

export interface PhoenixSkuItem {
  id: string | number
  name: string
  code: string
  price: number
  stock: number
  image?: string
  disabled?: boolean
}

const props = withDefaults(defineProps<{
  modelValue: PhoenixSkuItem[]
  currency?: string
  locale?: string
  disabled?: boolean
  maxItems?: number
  title?: string
}>(), {
  currency: 'CNY', locale: 'zh-CN', disabled: false, maxItems: 50, title: '规格与库存',
})
const emit = defineEmits<{
  'update:modelValue': [items: PhoenixSkuItem[]]
  change: [items: PhoenixSkuItem[]]
  add: [item: PhoenixSkuItem]
  remove: [item: PhoenixSkuItem]
}>()
const limit = computed(() => clampInteger(props.maxItems, 1, 200))
const normalized = computed(() => props.modelValue.slice(0, limit.value).map(normalize))

function normalize(item: PhoenixSkuItem): PhoenixSkuItem {
  return {
    ...item,
    name: String(item.name ?? '').slice(0, 80),
    code: String(item.code ?? '').slice(0, 64),
    price: clampNumber(item.price, 0, 999999999),
    stock: clampInteger(item.stock, 0, 999999),
  }
}
function update(index: number, patch: Partial<PhoenixSkuItem>) {
  const current = normalized.value[index]
  if (!current || props.disabled || current.disabled) return
  const items = normalized.value.map((item, itemIndex) => itemIndex === index ? normalize({ ...item, ...patch }) : item)
  emit('update:modelValue', items); emit('change', items)
}
function add() {
  if (props.disabled || normalized.value.length >= limit.value) return
  const item: PhoenixSkuItem = { id: `new-${normalized.value.length + 1}`, name: '新规格', code: '', price: 0, stock: 0 }
  emit('update:modelValue', [...normalized.value, item]); emit('add', item)
}
function remove(index: number) {
  const item = normalized.value[index]
  if (!item || props.disabled || item.disabled) return
  emit('update:modelValue', normalized.value.filter((_, itemIndex) => itemIndex !== index)); emit('remove', item)
}
</script>

<template>
  <section class="px-sku-editor" :aria-label="title">
    <header><h3>{{ title }}</h3><button type="button" :disabled="disabled || normalized.length >= limit" @click="add">添加规格</button></header>
    <p v-if="!normalized.length" class="px-commerce-empty" role="status">暂无规格</p>
    <div v-for="(item, index) in normalized" v-else :key="item.id" class="px-sku-editor__row">
      <span class="px-sku-editor__image"><img v-if="safeImageUrl(item.image)" :src="safeImageUrl(item.image)" :alt="item.name" loading="lazy"><span v-else aria-hidden="true">{{ item.name.slice(0, 1) || '规' }}</span></span>
      <label><span>规格名称</span><input :value="item.name" :disabled="disabled || item.disabled" maxlength="80" @input="update(index, { name: ($event.target as HTMLInputElement).value })"></label>
      <label><span>规格编码</span><input :value="item.code" :disabled="disabled || item.disabled" maxlength="64" @input="update(index, { code: ($event.target as HTMLInputElement).value })"></label>
      <label><span>价格</span><input type="number" inputmode="decimal" min="0" :value="item.price" :disabled="disabled || item.disabled" @change="update(index, { price: Number(($event.target as HTMLInputElement).value) })"><small>{{ formatCurrency(item.price, currency, locale) }}</small></label>
      <label><span>库存</span><input type="number" inputmode="numeric" min="0" :value="item.stock" :disabled="disabled || item.disabled" @change="update(index, { stock: Number(($event.target as HTMLInputElement).value) })"></label>
      <button type="button" :aria-label="`删除 ${item.name || '规格'}`" :disabled="disabled || item.disabled" @click="remove(index)">删除</button>
    </div>
  </section>
</template>
