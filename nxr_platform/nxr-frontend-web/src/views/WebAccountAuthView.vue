<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import { isCustomerSignedIn, loginCustomer, registerCustomer } from '../lib/customer'

const props = defineProps<{ mode: 'login' | 'register' }>()
const router = useRouter()
const route = useRoute()
const email = ref('')
const password = ref('')
const displayName = ref('')
const mobile = ref('')
const errorMessage = ref('')
const submitting = ref(false)

function nextPath() {
  const next = typeof route.query.next === 'string' ? route.query.next : ''
  return next.startsWith('/') && !next.startsWith('//') ? next : '/account/cards'
}

async function submit() {
  errorMessage.value = ''
  submitting.value = true
  try {
    if (props.mode === 'register') {
      await registerCustomer({ email: email.value.trim(), password: password.value, displayName: displayName.value.trim(), mobile: mobile.value.trim() })
    } else {
      await loginCustomer({ email: email.value.trim(), password: password.value })
    }
    await router.replace(nextPath())
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to complete sign-in.'
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  if (isCustomerSignedIn.value) void router.replace(nextPath())
})
</script>

<template>
  <LegacySiteNav active="account" />
  <main class="account-page">
    <section class="account-panel">
      <p class="section-tag">Collector account</p>
      <h1>{{ mode === 'register' ? 'Create your account' : 'Welcome back' }}</h1>
      <p class="account-copy">Use one account to manage your cards, grading orders, payment confirmations, and shipment updates.</p>
      <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>
      <form class="portal-form" @submit.prevent="submit">
        <label v-if="mode === 'register'">Display name<input v-model="displayName" type="text" maxlength="128" autocomplete="name" placeholder="How collectors should see you" /></label>
        <label>Email<input v-model="email" type="email" required autocomplete="email" placeholder="you@example.com" /></label>
        <label v-if="mode === 'register'">Mobile (optional)<input v-model="mobile" type="tel" maxlength="64" autocomplete="tel" /></label>
        <label>Password<input v-model="password" type="password" required minlength="8" autocomplete="current-password" placeholder="At least 8 characters" /></label>
        <button class="btn-primary form-submit" type="submit" :disabled="submitting">{{ submitting ? 'Please wait...' : mode === 'register' ? 'Create account' : 'Sign in' }}</button>
      </form>
      <p class="account-switch">
        <template v-if="mode === 'register'">Already have an account? <router-link to="/account/login">Sign in</router-link></template>
        <template v-else>New to NXR? <router-link to="/account/register">Create an account</router-link></template>
      </p>
    </section>
  </main>
  <LegacySiteFooter compact />
</template>
