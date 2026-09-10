<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import { clearCustomerSession, customerRequest } from '../lib/customer'

type Capability = { available: boolean; message: string }
const route = useRoute()
const router = useRouter()
const resetComplete = ref(false)
const token = computed(() => typeof route.query.token === 'string' ? route.query.token.trim() : '')
const email = ref('')
const password = ref('')
const passwordAgain = ref('')
const capability = ref<Capability>({ available: false, message: 'Checking email availability…' })
const submitting = ref(false)
const errorMessage = ref('')
const successMessage = ref('')

async function loadCapability() {
  if (token.value) return
  try {
    const status = await customerRequest<{ delivery: Capability }>('/api/customer/account/email-status', {}, false)
    capability.value = status.delivery
  } catch (error) {
    capability.value = { available: false, message: error instanceof Error ? error.message : 'Email delivery is unavailable.' }
  }
}

async function requestReset() {
  errorMessage.value = ''
  successMessage.value = ''
  submitting.value = true
  try {
    const result = await customerRequest<{ accepted: boolean; message: string }>('/api/customer/account/password-reset/request', {
      method: 'POST', body: JSON.stringify({ email: email.value.trim() }),
    }, false)
    successMessage.value = result.message
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to process the request.'
  } finally {
    submitting.value = false
  }
}

async function confirmReset() {
  errorMessage.value = ''
  successMessage.value = ''
  if (password.value !== passwordAgain.value) {
    errorMessage.value = 'Passwords do not match.'
    return
  }
  submitting.value = true
  try {
    const result = await customerRequest<{ success: boolean; message: string }>('/api/customer/account/password-reset/confirm', {
      method: 'POST', body: JSON.stringify({ token: token.value, password: password.value }),
    }, false)
    successMessage.value = result.message
    password.value = ''
    passwordAgain.value = ''
    resetComplete.value = true
    clearCustomerSession()
    await router.replace({ query: {} })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to reset the password.'
  } finally {
    submitting.value = false
  }
}

onMounted(loadCapability)
</script>

<template>
  <LegacySiteNav active="account" />
  <main class="account-page">
    <section class="account-panel">
      <p class="section-tag">Collector account</p>
      <h1>{{ token ? 'Choose a new password' : 'Reset your password' }}</h1>
      <p class="account-copy">
        {{ token ? 'The reset link is single-use. Completing this step signs your account out on every device.' : 'Enter the email address used for your NXR account.' }}
      </p>
      <p v-if="!token && !capability.available" class="availability-note">{{ capability.message }}</p>
      <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>
      <p v-if="successMessage" class="success-note">{{ successMessage }}</p>

      <form v-if="token && !resetComplete" class="portal-form" @submit.prevent="confirmReset">
        <label>New password<input v-model="password" type="password" minlength="8" maxlength="200" required autocomplete="new-password" /></label>
        <label>Confirm password<input v-model="passwordAgain" type="password" minlength="8" maxlength="200" required autocomplete="new-password" /></label>
        <button class="btn-primary form-submit" type="submit" :disabled="submitting">{{ submitting ? 'Updating…' : 'Update password' }}</button>
      </form>
      <form v-else-if="!resetComplete" class="portal-form" @submit.prevent="requestReset">
        <label>Email<input v-model="email" type="email" maxlength="254" required autocomplete="email" /></label>
        <button class="btn-primary form-submit" type="submit" :disabled="submitting || !capability.available">{{ submitting ? 'Submitting…' : 'Send reset link' }}</button>
      </form>
      <p class="account-switch"><router-link to="/account/login">Return to sign in</router-link></p>
    </section>
  </main>
  <LegacySiteFooter compact />
</template>

<style scoped>
.availability-note, .success-note { margin: 0 0 18px; padding: 12px; line-height: 1.5; }
.availability-note { border: 1px solid #5a4320; color: #f5c842; background: #1b170d; }
.success-note { border: 1px solid #28593b; color: #8ed5a6; background: #0e1a12; }
button:disabled { cursor: not-allowed; opacity: .55; }
</style>
