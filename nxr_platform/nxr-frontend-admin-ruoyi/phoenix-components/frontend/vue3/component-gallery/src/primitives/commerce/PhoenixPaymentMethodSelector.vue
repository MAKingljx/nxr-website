<script setup lang="ts">
import { computed, useId } from 'vue'
import { safeImageUrl } from './utils'

export interface PhoenixPaymentMethod {
  code: string
  label: string
  description?: string
  icon?: string
  disabled?: boolean
}
const props = withDefaults(defineProps<{
  modelValue?: string
  methods: PhoenixPaymentMethod[]
  title?: string
  disabled?: boolean
  confirmText?: string
}>(), { modelValue: '', title: '选择支付方式', disabled: false, confirmText: '确认支付方式' })
const emit = defineEmits<{
  'update:modelValue': [code: string]
  change: [method: PhoenixPaymentMethod]
  confirm: [method: PhoenixPaymentMethod]
}>()
const name = `phoenix-payment-method-${useId()}`
const selected = computed(() => props.methods.find((method) => method.code === props.modelValue && !method.disabled) ?? null)
function choose(method: PhoenixPaymentMethod) {
  if (props.disabled || method.disabled) return
  emit('update:modelValue', method.code); emit('change', method)
}
function confirm() { if (!props.disabled && selected.value) emit('confirm', selected.value) }
</script>

<template>
  <fieldset class="px-payment-method" :disabled="disabled">
    <legend>{{ title }}</legend>
    <label v-for="method in methods" :key="method.code" :class="{ 'is-selected': modelValue === method.code, 'is-disabled': method.disabled }">
      <input type="radio" :name="name" :value="method.code" :checked="modelValue === method.code" :disabled="disabled || method.disabled" @change="choose(method)">
      <span class="px-payment-method__icon"><img v-if="safeImageUrl(method.icon)" :src="safeImageUrl(method.icon)" alt="" loading="lazy"><span v-else aria-hidden="true">付</span></span>
      <span><strong>{{ method.label }}</strong><small v-if="method.description">{{ method.description }}</small></span>
    </label>
    <p class="px-commerce-hint">仅选择支付方式，不会在此发起扣款</p>
    <button class="px-commerce-primary" type="button" :disabled="disabled || !selected" @click="confirm">{{ confirmText }}</button>
  </fieldset>
</template>
