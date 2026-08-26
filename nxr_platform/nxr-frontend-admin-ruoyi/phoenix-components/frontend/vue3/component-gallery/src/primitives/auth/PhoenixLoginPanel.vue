<script setup lang="ts">
import { computed, useId } from 'vue'

export interface PhoenixLoginCredentials {
  username: string
  password: string
  remember: boolean
}

const props = withDefaults(defineProps<{
  username?: string
  password?: string
  remember?: boolean
  title?: string
  usernameLabel?: string
  passwordLabel?: string
  submitText?: string
  busyText?: string
  error?: string
  busy?: boolean
  disabled?: boolean
  showRemember?: boolean
}>(), {
  username: '',
  password: '',
  remember: false,
  title: '欢迎登录',
  usernameLabel: '用户名',
  passwordLabel: '口令',
  submitText: '登录',
  busyText: '正在登录',
  error: '',
  busy: false,
  disabled: false,
  showRemember: true,
})

const emit = defineEmits<{
  'update:username': [value: string]
  'update:password': [value: string]
  'update:remember': [value: boolean]
  submit: [credentials: PhoenixLoginCredentials]
}>()

const uid = useId()
const usernameId = computed(() => `phoenix-login-username-${uid}`)
const passwordId = computed(() => `phoenix-login-password-${uid}`)
const errorId = computed(() => `phoenix-login-error-${uid}`)
const canSubmit = computed(() => Boolean(props.username.trim() && props.password) && !props.busy && !props.disabled)

function inputValue(event: Event) {
  return (event.target as HTMLInputElement).value
}

function submit() {
  if (!canSubmit.value) return
  emit('submit', {
    username: props.username.trim(),
    password: props.password,
    remember: props.remember,
  })
}
</script>

<template>
  <form class="px-login-panel" :aria-busy="busy" @submit.prevent="submit">
    <header class="px-login-panel__header">
      <slot name="brand"></slot>
      <h2>{{ title }}</h2>
    </header>

    <div class="px-login-panel__field">
      <label :for="usernameId">{{ usernameLabel }}</label>
      <input
        :id="usernameId"
        :value="username"
        name="username"
        type="text"
        autocomplete="username"
        :disabled="disabled || busy"
        required
        @input="emit('update:username', inputValue($event))"
      />
    </div>

    <div class="px-login-panel__field">
      <label :for="passwordId">{{ passwordLabel }}</label>
      <input
        :id="passwordId"
        :value="password"
        name="password"
        type="password"
        autocomplete="current-password"
        :aria-describedby="error ? errorId : undefined"
        :disabled="disabled || busy"
        required
        @input="emit('update:password', inputValue($event))"
      />
    </div>

    <label v-if="showRemember" class="px-login-panel__remember">
      <input
        type="checkbox"
        name="remember"
        :checked="remember"
        :disabled="disabled || busy"
        @change="emit('update:remember', ($event.target as HTMLInputElement).checked)"
      />
      <span>记住登录状态</span>
    </label>

    <p v-if="error" :id="errorId" class="px-login-panel__error" role="alert">{{ error }}</p>

    <button type="submit" class="px-login-panel__submit" :disabled="!canSubmit">
      <span v-if="busy" class="px-login-panel__spinner" aria-hidden="true"></span>
      {{ busy ? busyText : submitText }}
    </button>

    <footer v-if="$slots.footer" class="px-login-panel__footer"><slot name="footer"></slot></footer>
  </form>
</template>
