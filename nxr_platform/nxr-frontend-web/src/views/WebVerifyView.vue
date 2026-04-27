<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchOverview, type FeaturedCard } from '../lib/api'

const router = useRouter()
const certId = ref('')
const featuredCards = ref<FeaturedCard[]>([])
const errorMessage = ref('')

function submitVerify() {
  const normalized = certId.value.trim()
  if (!normalized) {
    return
  }
  router.push(`/card/${encodeURIComponent(normalized)}`)
}

onMounted(async () => {
  try {
    const overview = await fetchOverview()
    featuredCards.value = overview.featuredCards
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Failed to load verify helper data'
  }
})
</script>

<template>
  <main class="verify-shell">
    <section class="verify-panel">
      <router-link class="home-link" to="/">NXR Grading</router-link>
      <p class="eyebrow">Public Verify</p>
      <h1>Verify a certificate by exact ID.</h1>
      <p>Enter the slab certificate ID to open the published grading record. Lookup is case-insensitive for user convenience, but records remain exact on the backend.</p>

      <form class="verify-form" @submit.prevent="submitVerify">
        <input v-model="certId" type="text" placeholder="Certificate ID, e.g. VRA003" />
        <button type="submit">Verify Card</button>
      </form>

      <p class="hint">
        Try one of these seeded certificates:
        <button v-for="card in featuredCards" :key="card.certId" class="hint-chip" type="button" @click="certId = card.certId">
          {{ card.certId }}
        </button>
      </p>
      <p v-if="errorMessage" class="error-banner">{{ errorMessage }}</p>
    </section>
  </main>
</template>

<style scoped>
.verify-shell {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
}

.verify-panel {
  width: min(720px, 100%);
  padding: 36px;
  border: 1px solid var(--line);
  border-radius: 28px;
  background: rgba(255, 255, 255, 0.85);
}

.home-link {
  display: inline-flex;
  margin-bottom: 12px;
  font-size: 0.95rem;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.eyebrow {
  margin: 0 0 12px;
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  font-weight: 700;
  color: var(--accent);
}

h1 {
  margin: 0 0 16px;
  font-size: clamp(2rem, 4vw, 3.5rem);
  line-height: 1;
}

p {
  margin: 0;
  color: var(--muted);
}

.verify-form {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 12px;
  margin-top: 24px;
}

input,
button {
  height: 52px;
  border-radius: 16px;
  border: 1px solid var(--line);
  padding: 0 16px;
}

button {
  background: var(--deep);
  color: #fff;
  border: none;
  font-weight: 700;
}

.hint {
  margin-top: 18px;
  line-height: 1.8;
}

.hint-chip {
  margin-left: 8px;
  height: auto;
  padding: 8px 12px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: rgba(17, 21, 31, 0.04);
  color: var(--text);
}

.error-banner {
  margin-top: 16px;
  padding: 12px 14px;
  border-radius: 14px;
  background: rgba(204, 75, 31, 0.12);
  color: var(--accent-dark);
}

@media (max-width: 640px) {
  .verify-form {
    grid-template-columns: 1fr;
  }
}
</style>
