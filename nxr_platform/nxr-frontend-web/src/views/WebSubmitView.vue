<script setup lang="ts">
import { onMounted, ref } from 'vue'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import { fetchWaitlistCount, joinWaitlist } from '../lib/api'
import { isCustomerSignedIn } from '../lib/customer'

const waitlistCount = ref<number | null>(null)
const email = ref('')
const errorMessage = ref('')
const confirmedEmail = ref('')
const isSubmitting = ref(false)

async function refreshWaitlistCount() {
  try {
    const response = await fetchWaitlistCount()
    waitlistCount.value = response.count
  } catch {
    waitlistCount.value = null
  }
}

async function submitWaitlist() {
  errorMessage.value = ''
  isSubmitting.value = true

  try {
    const response = await joinWaitlist(email.value.trim())
    waitlistCount.value = response.count
    confirmedEmail.value = response.email
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Failed to join. Please try again.'
  } finally {
    isSubmitting.value = false
  }
}

onMounted(() => {
  void refreshWaitlistCount()
})
</script>

<template>
  <LegacySiteNav active="submit" :cta-href="isCustomerSignedIn ? '/submit/order' : '/account/register?next=/submit/order'" :cta-label="isCustomerSignedIn ? 'Start order' : 'Create account'" />

  <main class="wl-hero">
    <div class="wl-inner">
      <div class="wl-badge">
        <span class="dot"></span>
        Currently in Private Beta
      </div>
      <h1 class="wl-h1">We're grading<br />by <span>invitation</span> only.</h1>
      <p class="wl-sub">
        NXR is currently in closed beta — we're working with a select group of collectors to refine our AI grading
        system before opening to the public.
      </p>
      <p class="wl-note">Leave your email and you'll be the first to know when we open up.</p>
      <div class="submit-order-callout">
        <div><strong>Customer grading orders are available locally.</strong><span>Create an order, submit payment confirmation, and follow inbound, grading, and return-shipment progress.</span></div>
        <router-link class="btn-primary" :to="isCustomerSignedIn ? '/submit/order' : '/account/register?next=/submit/order'">{{ isCustomerSignedIn ? 'Start grading order' : 'Create collector account' }}</router-link>
      </div>
      <div class="wl-counter">
        <div class="wl-stat">
          <div class="wl-stat-num">{{ waitlistCount === null ? '—' : waitlistCount.toLocaleString() }}</div>
          <div class="wl-stat-lbl">On Waitlist</div>
        </div>
        <div class="wl-stat">
          <div class="wl-stat-num">Q3 2026</div>
          <div class="wl-stat-lbl">Target Launch</div>
        </div>
        <div class="wl-stat">
          <div class="wl-stat-num">Free</div>
          <div class="wl-stat-lbl">To Join</div>
        </div>
      </div>

      <div v-if="!confirmedEmail" id="formWrap">
        <form class="wl-form" @submit.prevent="submitWaitlist">
          <input v-model="email" class="wl-input" type="email" placeholder="your@email.com" required autocomplete="email" />
          <button class="wl-btn" type="submit" :disabled="isSubmitting">{{ isSubmitting ? 'Joining…' : 'Notify Me →' }}</button>
        </form>
        <p class="wl-disclaimer">
          No spam, ever. We'll only email you when we're ready to open up. <router-link to="/faq">Privacy policy.</router-link>
        </p>
        <p v-if="errorMessage" class="wl-error">{{ errorMessage }}</p>
      </div>

      <div v-else class="wl-success">
        <div class="check">✅</div>
        <h3>You're on the list.</h3>
        <p>
          We'll email <strong>{{ confirmedEmail }}</strong> as soon as NXR opens to the public.<br />
          In the meantime, follow us for updates.
        </p>
      </div>

      <div class="wl-features">
        <div class="wl-feat"><span class="wl-feat-icon">🤖</span> AI + Human dual grading</div>
        <div class="wl-feat"><span class="wl-feat-icon">🔒</span> NFC tamper-proof slabs</div>
        <div class="wl-feat"><span class="wl-feat-icon">📊</span> Full sub-score transparency</div>
        <div class="wl-feat"><span class="wl-feat-icon">⚡</span> Faster turnaround</div>
      </div>
    </div>
  </main>

  <LegacySiteFooter compact />
</template>
