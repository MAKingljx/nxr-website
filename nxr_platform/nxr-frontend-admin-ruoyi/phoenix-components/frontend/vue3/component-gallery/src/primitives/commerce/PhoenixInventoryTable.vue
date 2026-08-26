<script setup lang="ts">
import { computed } from 'vue'
import { clampInteger } from './utils'

export interface PhoenixInventoryItem {
  id: string | number
  name: string
  sku?: string
  stock: number
  reserved?: number
  lowStockThreshold?: number
  disabled?: boolean
}
const props = withDefaults(defineProps<{
  items: PhoenixInventoryItem[]
  title?: string
  disabled?: boolean
  emptyText?: string
}>(), { title: '库存管理', disabled: false, emptyText: '暂无库存记录' })
const emit = defineEmits<{
  adjust: [item: PhoenixInventoryItem, stock: number]
  select: [item: PhoenixInventoryItem]
}>()
const rows = computed(() => props.items.map((item) => ({
  ...item,
  stock: clampInteger(item.stock, 0, 999999),
  reserved: clampInteger(item.reserved ?? 0, 0, 999999),
  lowStockThreshold: clampInteger(item.lowStockThreshold ?? 5, 0, 999999),
})))
function adjust(item: PhoenixInventoryItem, delta: number) {
  if (props.disabled || item.disabled) return
  emit('adjust', item, clampInteger(item.stock + delta, 0, 999999))
}
</script>

<template>
  <section class="px-inventory-table" :aria-label="title">
    <header><h3>{{ title }}</h3><span>{{ rows.length }} 项</span></header>
    <p v-if="!rows.length" class="px-commerce-empty" role="status">{{ emptyText }}</p>
    <div v-else class="px-commerce-table-wrap">
      <table>
        <thead><tr><th scope="col">商品</th><th scope="col">可用</th><th scope="col">占用</th><th scope="col">状态</th><th scope="col">调整</th></tr></thead>
        <tbody>
          <tr v-for="item in rows" :key="item.id">
            <th scope="row"><button type="button" :disabled="disabled || item.disabled" @click="emit('select', item)">{{ item.name }}<small v-if="item.sku">{{ item.sku }}</small></button></th>
            <td>{{ item.stock }}</td><td>{{ item.reserved }}</td>
            <td><span :class="{ 'is-low': item.stock <= item.lowStockThreshold }">{{ item.stock <= item.lowStockThreshold ? '库存不足' : '库存正常' }}</span></td>
            <td><button type="button" :aria-label="`减少 ${item.name} 库存`" :disabled="disabled || item.disabled || item.stock <= 0" @click="adjust(item, -1)">−</button><button type="button" :aria-label="`增加 ${item.name} 库存`" :disabled="disabled || item.disabled" @click="adjust(item, 1)">+</button></td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
