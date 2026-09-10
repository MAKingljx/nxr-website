<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import { customerRequest, isCustomerSignedIn } from '../lib/customer'

type Capability = { available: boolean; message: string }
type EmailStatus = { email: string | null; verified: boolean | null; delivery: Capability }
const route = useRoute()
const token = computed(() => typeof route.query.token === 'string' ? route.query.token.trim() : '')
const status = ref<EmailStatus | null>(null)
const loading = ref(true)
const submitting = ref(false)
const errorMessage = ref('')
const successMessage = ref('')

async function load() {
  try {
    if (token.value) {
      const result = await customerRequest<{ success: boolean; message: string }>('/api/customer/account/email-verification/confirm', {
        method: 'POST', body: JSON.stringify({ token: token.value }),
      }, false)
      successMessage.value = result.message
    } else {
      status.value = await customerRequest<EmailStatus>('/api/customer/account/email-status', {}, isCustomerSignedIn.value)
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to verify this email address.'
  } finally {
    loading.value = false
  }
}

async function requestVerification() {
  submitting.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const result = await customerRequest<{ queued: boolean; message: string }>('/api/customer/account/email-verification/request', { method: 'POST' })
    if (result.queued) successMessage.value = result.message
    else errorMessage.value = result.message
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unable to request verification.'
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <LegacySiteNav active="account" />
  <main class="account-page">
    <section class="account-panel">
      <p class="section-tag">Collector account</p>
      <h1>Email verification</h1>
      <p v-if="loading" class="account-copy">Checking verification status…</p>
      <template v-else>
        <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>
        <p v-if="successMessage" class="success-note">{{ successMessage }}</p>
        <template v-if="!token && status">
          <p v-if="status.verified" class="success-note">{{ status.email }} is verified.</p>
          <p v-else-if="!isCustomerSignedIn" class="account-copy">Sign in to request verification for your account email.</p>
          <template v-else>
            <p class="account-copy">Send a single-use verification link to {{ status.email }}.</p>
            <p v-if="!status.delivery.available" class="availability-note">{{ status.delivery.message }}</p>
            <button class="btn-primary form-submit" type="button" :disabled="submitting || !status.delivery.available" @click="requestVerification">
              {{ submitting ? 'Queuing…' : 'Send verification link' }}
            </button>
          </template>
        </template>
      </template>
      <p class="account-switch"><router-link :to="isCustomerSignedIn ? '/account/cards' : '/account/login'">Continue to account</router-link></p>
    </section>
  </main>
  <LegacySiteFooter compact />
</template>

<style scoped>
.availability-note, .success-note { margin: 0 0 18px; padding: 12px; line-height: 1.5; }
.availability-note { border: 1px solid #5a4320; color: #f5c842; background: #1b170d; }
.success-note { border: 1px solid #28593b; color: #8ed5a6; background: #0e1a12; }
button { width: 100%; border: 0; }
button:disabled { cursor: not-allowed; opacity: .55; }
</style>
