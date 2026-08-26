<script setup lang="ts">
import { computed } from 'vue'
import { clampNumber, formatCurrency } from './utils'

export interface PhoenixRefundRequest {
  amount: number
  reasonCode: string
  note: string
}
const props = withDefaults(defineProps<{
  amount?: number
  reasonCode?: string
  note?: string
  maxAmount?: number
  reasons?: { code: string; label: string; disabled?: boolean }[]
  currency?: string
  locale?: string
  disabled?: boolean
  submitting?: boolean
}>(), {
  amount: 0, reasonCode: '', note: '', maxAmount: 0,
  reasons: () => [{ code: 'changed-mind', label: '不再需要' }, { code: 'quality', label: '质量问题' }, { code: 'other', label: '其他原因' }],
  currency: 'CNY', locale: 'zh-CN', disabled: false, submitting: false,
})
const emit = defineEmits<{
  'update:amount': [amount: number]
  'update:reasonCode': [reasonCode: string]
  'update:note': [note: string]
  submit: [request: PhoenixRefundRequest]
  cancel: []
}>()
const limit = computed(() => clampNumber(props.maxAmount, 0, 999999999))
const safeAmount = computed(() => clampNumber(props.amount, 0, limit.value))
const safeNote = computed(() => String(props.note ?? '').slice(0, 300))
const validReason = computed(() => props.reasons.some((reason) => reason.code === props.reasonCode && !reason.disabled))
const valid = computed(() => safeAmount.value > 0 && validReason.value)
function submit() {
  if (!props.disabled && !props.submitting && valid.value) emit('submit', { amount: safeAmount.value, reasonCode: props.reasonCode, note: safeNote.value })
}
</script>

<template>
  <form class="px-refund-panel" aria-label="退款申请" @submit.prevent="submit">
    <header><h3>申请退款</h3><strong>最多 {{ formatCurrency(limit, currency, locale) }}</strong></header>
    <label><span>退款金额</span><input type="number" inputmode="decimal" min="0" :max="limit" :value="safeAmount" :disabled="disabled || submitting" @input="emit('update:amount', clampNumber(Number(($event.target as HTMLInputElement).value), 0, limit))"></label>
    <label><span>退款原因</span><select :value="reasonCode" required :disabled="disabled || submitting" @change="emit('update:reasonCode', ($event.target as HTMLSelectElement).value)"><option value="">请选择退款原因</option><option v-for="reason in reasons" :key="reason.code" :value="reason.code" :disabled="reason.disabled">{{ reason.label }}</option></select></label>
    <label><span>补充说明</span><textarea :value="safeNote" maxlength="300" :disabled="disabled || submitting" @input="emit('update:note', ($event.target as HTMLTextAreaElement).value.slice(0, 300))"></textarea><small>{{ safeNote.length }}/300</small></label>
    <p class="px-commerce-hint">此操作仅提交退款请求，不直接退回资金</p>
    <footer><button type="button" :disabled="disabled || submitting" @click="emit('cancel')">取消</button><button class="is-primary" type="submit" :disabled="disabled || submitting || !valid">{{ submitting ? '提交中' : '提交申请' }}</button></footer>
  </form>
</template>
