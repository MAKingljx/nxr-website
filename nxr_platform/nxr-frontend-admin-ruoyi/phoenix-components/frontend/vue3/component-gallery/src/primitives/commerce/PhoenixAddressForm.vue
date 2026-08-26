<script setup lang="ts">
import { computed } from 'vue'

export interface PhoenixAddressValue {
  recipient: string
  phone: string
  province: string
  city: string
  district: string
  address: string
  postalCode?: string
  isDefault?: boolean
}
const props = withDefaults(defineProps<{
  modelValue: PhoenixAddressValue
  title?: string
  disabled?: boolean
  submitting?: boolean
}>(), { title: '填写地址', disabled: false, submitting: false })
const emit = defineEmits<{
  'update:modelValue': [value: PhoenixAddressValue]
  change: [value: PhoenixAddressValue]
  submit: [value: PhoenixAddressValue]
  cancel: []
}>()
const normalized = computed<PhoenixAddressValue>(() => ({
  recipient: String(props.modelValue.recipient ?? '').slice(0, 40),
  phone: String(props.modelValue.phone ?? '').slice(0, 24),
  province: String(props.modelValue.province ?? '').slice(0, 40),
  city: String(props.modelValue.city ?? '').slice(0, 40),
  district: String(props.modelValue.district ?? '').slice(0, 40),
  address: String(props.modelValue.address ?? '').slice(0, 160),
  postalCode: String(props.modelValue.postalCode ?? '').slice(0, 12),
  isDefault: Boolean(props.modelValue.isDefault),
}))
const valid = computed(() => Boolean(normalized.value.recipient.trim() && /^[+\d][\d\s-]{5,23}$/.test(normalized.value.phone.trim()) && normalized.value.province.trim() && normalized.value.city.trim() && normalized.value.district.trim() && normalized.value.address.trim()))
function update<K extends keyof PhoenixAddressValue>(key: K, value: PhoenixAddressValue[K]) {
  if (props.disabled || props.submitting) return
  const next = { ...normalized.value, [key]: value }
  emit('update:modelValue', next); emit('change', next)
}
function submit() { if (!props.disabled && !props.submitting && valid.value) emit('submit', normalized.value) }
</script>

<template>
  <form class="px-address-form" :aria-label="title" @submit.prevent="submit">
    <h3>{{ title }}</h3>
    <div class="px-address-form__grid">
      <label><span>收货人</span><input :value="normalized.recipient" autocomplete="name" maxlength="40" required :disabled="disabled || submitting" @input="update('recipient', ($event.target as HTMLInputElement).value)"></label>
      <label><span>联系电话</span><input :value="normalized.phone" type="tel" autocomplete="tel" maxlength="24" required :aria-invalid="normalized.phone ? !/^[+\d][\d\s-]{5,23}$/.test(normalized.phone.trim()) : undefined" :disabled="disabled || submitting" @input="update('phone', ($event.target as HTMLInputElement).value)"></label>
      <label><span>省份</span><input :value="normalized.province" autocomplete="address-level1" required :disabled="disabled || submitting" @input="update('province', ($event.target as HTMLInputElement).value)"></label>
      <label><span>城市</span><input :value="normalized.city" autocomplete="address-level2" required :disabled="disabled || submitting" @input="update('city', ($event.target as HTMLInputElement).value)"></label>
      <label><span>区县</span><input :value="normalized.district" autocomplete="address-level3" required :disabled="disabled || submitting" @input="update('district', ($event.target as HTMLInputElement).value)"></label>
      <label><span>邮政编码</span><input :value="normalized.postalCode" inputmode="numeric" autocomplete="postal-code" maxlength="12" :disabled="disabled || submitting" @input="update('postalCode', ($event.target as HTMLInputElement).value)"></label>
      <label class="is-wide"><span>详细地址</span><textarea :value="normalized.address" autocomplete="street-address" maxlength="160" required :disabled="disabled || submitting" @input="update('address', ($event.target as HTMLTextAreaElement).value)"></textarea></label>
      <label class="px-address-form__default"><input type="checkbox" :checked="normalized.isDefault" :disabled="disabled || submitting" @change="update('isDefault', ($event.target as HTMLInputElement).checked)"><span>设为默认地址</span></label>
    </div>
    <p v-if="!valid" class="px-commerce-hint" role="status">请完整填写有效的收货信息</p>
    <footer><button type="button" :disabled="disabled || submitting" @click="emit('cancel')">取消</button><button class="is-primary" type="submit" :disabled="disabled || submitting || !valid">{{ submitting ? '提交中' : '保存地址' }}</button></footer>
  </form>
</template>
