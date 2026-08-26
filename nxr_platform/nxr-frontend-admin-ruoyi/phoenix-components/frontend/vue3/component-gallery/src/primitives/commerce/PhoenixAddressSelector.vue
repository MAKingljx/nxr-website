<script setup lang="ts">
import { useId } from 'vue'

export interface PhoenixSavedAddress {
  id: string | number
  recipient: string
  phone: string
  region: string
  address: string
  isDefault?: boolean
  disabled?: boolean
}
const props = withDefaults(defineProps<{
  modelValue?: string | number | null
  addresses: PhoenixSavedAddress[]
  title?: string
  disabled?: boolean
  emptyText?: string
}>(), { modelValue: null, title: '收货地址', disabled: false, emptyText: '暂无可用地址' })
const emit = defineEmits<{
  'update:modelValue': [id: string | number]
  change: [address: PhoenixSavedAddress]
  add: []
  edit: [address: PhoenixSavedAddress]
}>()
const name = `phoenix-address-${useId()}`
function choose(address: PhoenixSavedAddress) {
  if (props.disabled || address.disabled) return
  emit('update:modelValue', address.id); emit('change', address)
}
</script>

<template>
  <fieldset class="px-address-selector" :disabled="disabled">
    <legend>{{ title }}</legend>
    <p v-if="!addresses.length" class="px-commerce-empty" role="status">{{ emptyText }}</p>
    <label v-for="address in addresses" v-else :key="address.id" :class="{ 'is-selected': modelValue === address.id, 'is-disabled': address.disabled }">
      <input type="radio" :name="name" :value="address.id" :checked="modelValue === address.id" :disabled="disabled || address.disabled" @change="choose(address)">
      <span><strong>{{ address.recipient }}<small v-if="address.isDefault">默认</small></strong><span>{{ address.phone }}</span><span>{{ address.region }} {{ address.address }}</span></span>
      <button type="button" :aria-label="`编辑 ${address.recipient} 的地址`" :disabled="disabled || address.disabled" @click.prevent="emit('edit', address)">编辑</button>
    </label>
    <button class="px-commerce-secondary" type="button" :disabled="disabled" @click="emit('add')">新增地址</button>
  </fieldset>
</template>
