<script setup lang="ts">
import { computed } from 'vue'

export interface PhoenixPasswordChangeValue { currentPassword: string; newPassword: string; confirmPassword: string }
const props = withDefaults(defineProps<{
  modelValue?: PhoenixPasswordChangeValue
  revealed?: boolean
  minLength?: number
  submitting?: boolean
  disabled?: boolean
  title?: string
  username?: string
}>(), {
  modelValue: () => ({ currentPassword: '', newPassword: '', confirmPassword: '' }), revealed: false, minLength: 12,
  submitting: false, disabled: false, title: '修改密码', username: '',
})
const emit = defineEmits<{
  'update:modelValue': [value: PhoenixPasswordChangeValue]
  'update:revealed': [value: boolean]
  submit: [value: PhoenixPasswordChangeValue]
}>()
const safeMin = computed(() => Number.isFinite(props.minLength) ? Math.min(128, Math.max(8, Math.floor(props.minLength))) : 12)
const score = computed(() => {
  const value = props.modelValue.newPassword
  return [value.length >= safeMin.value, /[a-z]/.test(value) && /[A-Z]/.test(value), /\d/.test(value), /[^a-zA-Z0-9]/.test(value)].filter(Boolean).length
})
const valid = computed(() => Boolean(props.modelValue.currentPassword) && props.modelValue.newPassword.length >= safeMin.value && props.modelValue.newPassword === props.modelValue.confirmPassword)
function update(key: keyof PhoenixPasswordChangeValue, value: string) { emit('update:modelValue', { ...props.modelValue, [key]: value }) }
</script>

<template>
  <form class="px-password-change" :aria-label="title" @submit.prevent="valid && emit('submit', modelValue)">
    <label class="px-admin-sr-only"><span>用户名</span><input type="text" name="username" autocomplete="username" :value="username" readonly></label>
    <header><h3>{{ title }}</h3><button type="button" class="is-quiet" :aria-pressed="revealed" :disabled="disabled || submitting" @click="emit('update:revealed', !revealed)">{{ revealed ? '隐藏密码' : '显示密码' }}</button></header>
    <label><span>当前密码</span><input :type="revealed ? 'text' : 'password'" :value="modelValue.currentPassword" autocomplete="current-password" :disabled="disabled || submitting" @input="update('currentPassword', ($event.target as HTMLInputElement).value)"></label>
    <label><span>新密码</span><input :type="revealed ? 'text' : 'password'" :value="modelValue.newPassword" :minlength="safeMin" maxlength="128" autocomplete="new-password" :disabled="disabled || submitting" @input="update('newPassword', ($event.target as HTMLInputElement).value)"></label>
    <div class="px-password-change__strength" role="meter" aria-label="密码强度" aria-valuemin="0" aria-valuemax="4" :aria-valuenow="score"><span v-for="index in 4" :key="index" :class="{ 'is-active': index <= score }"></span></div>
    <small>至少 {{ safeMin }} 个字符，建议混合大小写字母、数字和符号。</small>
    <label><span>确认新密码</span><input :type="revealed ? 'text' : 'password'" :value="modelValue.confirmPassword" autocomplete="new-password" :disabled="disabled || submitting" :aria-invalid="Boolean(modelValue.confirmPassword && modelValue.newPassword !== modelValue.confirmPassword)" @input="update('confirmPassword', ($event.target as HTMLInputElement).value)"></label>
    <p v-if="modelValue.confirmPassword && modelValue.newPassword !== modelValue.confirmPassword" role="alert">两次输入的新密码不一致</p>
    <button type="submit" :disabled="disabled || submitting || !valid">{{ submitting ? '提交中' : '确认修改' }}</button>
  </form>
</template>
