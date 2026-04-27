<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { fetchPublicCard, type PublicCardDetail } from '../lib/api'

const props = defineProps<{ certId: string }>()

const card = ref<PublicCardDetail | null>(null)
const isLoading = ref(false)
const errorMessage = ref('')

const scoreRows = computed(() => {
  if (!card.value) {
    return []
  }

  return [
    { label: 'Centering', value: card.value.centeringScore },
    { label: 'Edges', value: card.value.edgesScore },
    { label: 'Corners', value: card.value.cornersScore },
    { label: 'Surface', value: card.value.surfaceScore },
  ]
})

const publishedDate = computed(() => {
  if (!card.value?.publishedAt) {
    return 'Not published'
  }

  return new Intl.DateTimeFormat('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  }).format(new Date(card.value.publishedAt))
})

async function loadCard(nextCertId: string) {
  isLoading.value = true
  errorMessage.value = ''

  try {
    card.value = await fetchPublicCard(nextCertId)
  } catch (error) {
    card.value = null
    errorMessage.value = error instanceof Error ? error.message : 'Failed to load certificate'
  } finally {
    isLoading.value = false
  }
}

watch(
  () => props.certId,
  (nextCertId) => {
    void loadCard(nextCertId)
  },
  { immediate: true },
)
</script>

<template>
  <main class="card-shell">
    <section v-if="isLoading" class="card-panel solo-panel">
      <p class="eyebrow">Loading</p>
      <h1>{{ certId }}</h1>
      <p>Loading certificate data from the Java backend.</p>
    </section>

    <section v-else-if="card" class="card-layout">
      <div class="card-images">
        <img :src="card.frontImageUrl" :alt="`${card.cardName} front`" />
        <img :src="card.backImageUrl" :alt="`${card.cardName} back`" />
      </div>

      <div class="card-panel">
        <router-link class="back-link" to="/verify">Back to Verify</router-link>
        <p class="eyebrow">Published Certificate</p>
        <h1>{{ card.certId }}</h1>
        <p class="hero-copy">{{ card.cardName }}</p>

        <div class="grade-banner">
          <strong>{{ card.finalGradeValue }}</strong>
          <span>{{ card.finalGradeLabel }}</span>
        </div>

        <div class="detail-grid">
          <article>
            <span>Brand</span>
            <strong>{{ card.brandName }}</strong>
          </article>
          <article>
            <span>Set</span>
            <strong>{{ card.setName }}</strong>
          </article>
          <article>
            <span>Card Number</span>
            <strong>{{ card.cardNumber }}</strong>
          </article>
          <article>
            <span>Language</span>
            <strong>{{ card.languageCode }}</strong>
          </article>
          <article>
            <span>Population</span>
            <strong>{{ card.populationValue }}</strong>
          </article>
          <article>
            <span>Published</span>
            <strong>{{ publishedDate }}</strong>
          </article>
        </div>

        <div class="score-grid">
          <article v-for="row in scoreRows" :key="row.label">
            <span>{{ row.label }}</span>
            <strong>{{ row.value }}</strong>
          </article>
        </div>

        <div class="note-panel">
          <h2>Decision Notes</h2>
          <p>{{ card.decisionNotes || 'No reviewer note attached.' }}</p>
          <a class="qr-link" :href="card.qrUrl" target="_blank" rel="noreferrer">Open QR target</a>
        </div>
      </div>
    </section>

    <section v-else class="card-panel solo-panel">
      <p class="eyebrow">Certificate Missing</p>
      <h1>{{ certId }}</h1>
      <p>{{ errorMessage || 'This certificate was not found in the published dataset.' }}</p>
      <router-link class="back-link" to="/verify">Back to Verify</router-link>
    </section>
  </main>
</template>

<style scoped>
.card-shell {
  min-height: 100vh;
  padding: 24px;
}

.card-layout {
  width: min(1240px, 100%);
  margin: 0 auto;
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  gap: 24px;
}

.card-images {
  display: grid;
  gap: 18px;
}

.card-images img,
.card-panel {
  border-radius: 28px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.84);
}

.card-images img {
  width: 100%;
  min-height: 280px;
  object-fit: cover;
}

.card-panel {
  padding: 36px;
  box-shadow: 0 18px 56px rgba(17, 21, 31, 0.08);
}

.solo-panel {
  width: min(720px, 100%);
  margin: 0 auto;
}

.eyebrow {
  margin: 0 0 12px;
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  font-weight: 700;
  color: var(--accent);
}

.hero-copy {
  margin: 12px 0 0;
  font-size: 1.15rem;
}

h1 {
  margin: 0;
  font-size: clamp(2.2rem, 4vw, 4rem);
}

p {
  margin: 14px 0 0;
  color: var(--muted);
}

.grade-banner {
  display: flex;
  align-items: end;
  gap: 12px;
  margin-top: 24px;
  padding: 18px 20px;
  border-radius: 20px;
  background: rgba(204, 75, 31, 0.1);
}

.grade-banner strong {
  font-size: 2rem;
}

.detail-grid,
.score-grid {
  display: grid;
  gap: 14px;
  margin-top: 22px;
}

.detail-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.score-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.detail-grid article,
.score-grid article,
.note-panel {
  padding: 18px;
  border-radius: 18px;
  background: rgba(17, 21, 31, 0.04);
}

.detail-grid span,
.score-grid span {
  display: block;
  color: var(--muted);
}

.detail-grid strong,
.score-grid strong {
  display: block;
  margin-top: 8px;
}

.note-panel {
  margin-top: 22px;
}

.note-panel h2 {
  margin: 0;
}

.qr-link {
  display: inline-flex;
  margin-top: 18px;
  padding: 12px 16px;
  border-radius: 999px;
  border: 1px solid var(--line);
}

.back-link {
  display: inline-block;
  margin-bottom: 18px;
  padding: 12px 16px;
  border-radius: 999px;
  border: 1px solid var(--line);
}

@media (max-width: 960px) {
  .card-layout,
  .detail-grid,
  .score-grid {
    grid-template-columns: 1fr;
  }
}
</style>
