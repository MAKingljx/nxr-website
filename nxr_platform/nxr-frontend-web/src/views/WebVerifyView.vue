<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'

const router = useRouter()
const certId = ref('')
const errorMessage = ref('')

function submitVerify() {
  const normalized = certId.value.trim()
  if (!normalized) {
    errorMessage.value = 'Please enter a Certificate ID'
    return
  }

  errorMessage.value = ''
  router.push(`/card/${encodeURIComponent(normalized)}`)
}
</script>

<template>
  <LegacySiteNav active="verify" />

  <main class="verify-page">
    <div class="verify-box">
      <h1 class="verify-title">Verify Your Card</h1>
      <p class="verify-subtitle">
        Enter the certificate ID from your slab to verify authenticity and view the full grading report.
      </p>
      <div v-if="errorMessage" class="verify-error">{{ errorMessage }}</div>
      <form class="verify-form" @submit.prevent="submitVerify">
        <input
          v-model="certId"
          type="text"
          name="cert_id"
          placeholder="Certificate ID (e.g., NXR123456)"
          required
          autocomplete="off"
        />
        <button type="submit">Verify Card</button>
      </form>
      <p class="hint">The certificate ID is printed on your slab label.<br />ID is case-insensitive.</p>
    </div>
  </main>

  <LegacySiteFooter />
</template>
