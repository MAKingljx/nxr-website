<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import { fetchAiCharacterInfo, fetchPublicCard, type PublicCardDetail } from '../lib/api'
import {
  claimCustomerCard,
  customerSession,
  fetchCardCommunity,
  isCustomerSignedIn,
  transferCustomerCard,
  type CardCommunity,
} from '../lib/customer'

const props = defineProps<{ certId: string }>()
const router = useRouter()

const card = ref<PublicCardDetail | null>(null)
const isLoading = ref(false)
const hasLoaded = ref(false)
const isAiOpen = ref(false)
const isAiLoading = ref(false)
const aiHtml = ref('')
const aiError = ref('')
const aiLanguage = ref('en')
const placeholderImage = '/static/placeholder.png'
const community = ref<CardCommunity | null>(null)
const communityError = ref('')
const claimForm = ref({ visibility: 'public', note: '' })
const transferForm = ref({ recipientEmail: '', visibility: 'public', message: '' })
const savingCommunity = ref(false)

const isCurrentOwner = computed(() => community.value?.ownership?.customerId === customerSession.value?.customer.id)

const gradeText = computed(() => {
  if (!card.value) {
    return 'N/A'
  }

  return `${Number(card.value.finalGradeValue).toFixed(1)} (${card.value.finalGradeLabel})`
})

const displayTitle = computed(() => {
  if (!card.value) {
    return ''
  }

  return card.value.cardCategory === 'movie_film' ? card.value.movieName || card.value.cardName : card.value.cardName
})

const detailRows = computed(() => {
  if (!card.value) {
    return []
  }

  const detail = card.value
  const rows: Array<{ key: string; value: string | null | undefined }> = [
    { key: 'Category', value: detail.cardCategoryLabel || detail.cardCategory },
  ]

  if (detail.cardCategory === 'movie_film') {
    rows.push(
      { key: 'Movie name', value: detail.movieName || detail.cardName },
      { key: 'Release year', value: detail.releaseYear || detail.yearLabel },
      { key: 'Production company', value: detail.productionCompany || detail.brandName },
      { key: 'Film type', value: detail.filmType || detail.varietyName },
    )
  } else {
    rows.push(
      { key: 'Year', value: detail.yearLabel },
      { key: 'Brand', value: detail.brandName },
      { key: 'Set', value: detail.setName },
      { key: 'Card number', value: detail.cardNumber },
      { key: 'Language', value: detail.languageCode },
    )

    if (detail.cardCategory === 'sports_card') {
      rows.push({ key: 'Sports type', value: detail.sportsType })
    }

    if (detail.cardCategory === 'celebrity_card') {
      rows.push({ key: 'Group name', value: detail.groupName })
    }
  }

  rows.push({ key: 'Population', value: String(detail.populationValue) })
  return rows.filter((row) => row.value)
})

const subgrades = computed(() => {
  if (!card.value) {
    return []
  }

  return [
    {
      name: 'Centering',
      score: card.value.centeringScore,
      desc: 'Measures how evenly the image is positioned within the borders. Critical for vintage cards.',
    },
    {
      name: 'Edges',
      score: card.value.edgesScore,
      desc: 'Evaluates the condition of all four edges. Look for chipping, whitening, or wear.',
    },
    {
      name: 'Corners',
      score: card.value.cornersScore,
      desc: 'Assesses sharpness and wear on all four corners. The most common area for damage.',
    },
    {
      name: 'Surface',
      score: card.value.surfaceScore,
      desc: 'Checks for scratches, print defects, stains, or other surface imperfections.',
    },
  ]
})

function subgradeClass(score: number) {
  if (score >= 9.5) {
    return 'subgrade-perfect'
  }

  if (score >= 8) {
    return 'subgrade-good'
  }

  if (score >= 6) {
    return 'subgrade-fair'
  }

  return 'subgrade-poor'
}

function useFallbackImage(event: Event) {
  const image = event.target as HTMLImageElement
  if (image.src.endsWith('/static/placeholder.png')) {
    return
  }

  image.src = placeholderImage
}

async function loadCard(nextCertId: string) {
  isLoading.value = true
  hasLoaded.value = false

  try {
    card.value = await fetchPublicCard(nextCertId)
    community.value = await fetchCardCommunity(card.value.certId)
  } catch {
    card.value = null
    community.value = null
  } finally {
    isLoading.value = false
    hasLoaded.value = true
  }
}

async function signInForCommunity() {
  await router.push(`/account/login?next=/card/${encodeURIComponent(props.certId)}`)
}

async function claimCard() {
  if (!card.value) return
  if (!isCustomerSignedIn.value) {
    await signInForCommunity()
    return
  }
  savingCommunity.value = true
  communityError.value = ''
  try {
    community.value = await claimCustomerCard(card.value.certId, claimForm.value)
  } catch (error) {
    communityError.value = error instanceof Error ? error.message : 'Unable to bind this card.'
  } finally {
    savingCommunity.value = false
  }
}

async function transferCard() {
  if (!card.value) return
  savingCommunity.value = true
  communityError.value = ''
  try {
    community.value = await transferCustomerCard(card.value.certId, transferForm.value)
    transferForm.value = { recipientEmail: '', visibility: 'public', message: '' }
  } catch (error) {
    communityError.value = error instanceof Error ? error.message : 'Unable to transfer this card.'
  } finally {
    savingCommunity.value = false
  }
}

async function openAiInfo() {
  if (!card.value) {
    return
  }

  isAiOpen.value = true
  isAiLoading.value = true
  aiError.value = ''
  aiHtml.value = ''

  try {
    const response = await fetchAiCharacterInfo({
      certId: card.value.certId,
      brand: card.value.brandName || card.value.productionCompany || 'Unknown',
      character: displayTitle.value,
      language: aiLanguage.value,
    })
    aiHtml.value = response.html
  } catch {
    aiError.value = 'Unable to load character information at this time.'
  } finally {
    isAiLoading.value = false
  }
}

function closeAiInfo() {
  isAiOpen.value = false
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
  <LegacySiteNav active="verify" />

  <main class="card-page">
    <div v-if="isLoading" class="error-container">
      <div class="error-icon">🔍</div>
      <h1 class="error-title">Loading Card</h1>
      <p class="error-message">Loading certificate data from the NXR platform.</p>
    </div>

    <div v-else-if="card" class="card-layout">
      <div class="images-col">
        <div class="img-wrap">
          <img :src="card.frontImageUrl || placeholderImage" alt="Front" loading="eager" @error="useFallbackImage" />
          <div class="img-label">Front</div>
        </div>
        <div class="img-wrap">
          <img :src="card.backImageUrl || placeholderImage" alt="Back" loading="lazy" @error="useFallbackImage" />
          <div class="img-label">Back</div>
        </div>

        <div class="ai-character-section">
          <button class="ai-character-btn" type="button" @click="openAiInfo">
            <span class="ai-icon">🤖</span>
            <span class="ai-text">AI Character Info</span>
            <span class="ai-subtext">{{ card.cardCategory === 'movie_film' ? 'Learn about this film' : 'Learn about this character' }}</span>
          </button>
          <p class="ai-note">Powered by NXR AI cache • Available in 9 languages</p>
        </div>

        <div class="verification-info">
          <div class="verification-title">Verification</div>
          <p>
            This card has been authenticated and graded by NXR Grading. The certificate ID below can be used to verify
            this grade on our website.
          </p>
          <div class="verification-id">{{ card.certId }}</div>
        </div>
      </div>

      <div class="card-info">
        <h1 class="card-title">{{ displayTitle }}</h1>
        <div class="card-cert">Certificate ID: {{ card.certId }}</div>
        <div class="grade-badge">Final Grade: {{ gradeText }}</div>

        <div class="detail-grid">
          <div v-for="row in detailRows" :key="row.key" class="detail-row">
            <span class="detail-key">{{ row.key }}</span>
            <span class="detail-val">{{ row.value }}</span>
          </div>
        </div>

        <div class="subgrades-section">
          <div class="subgrades-header">
            <div class="subgrades-title">Sub-Grades Breakdown</div>
            <div class="subgrades-subtitle">Four critical components of the grade</div>
          </div>

          <div class="subgrades-grid">
            <div v-for="item in subgrades" :key="item.name" class="subgrade-card">
              <div class="subgrade-header">
                <div class="subgrade-name">{{ item.name }}</div>
                <div class="subgrade-score" :class="subgradeClass(item.score)">
                  {{ Number(item.score).toFixed(1) }}
                </div>
              </div>
              <div class="subgrade-desc">{{ item.desc }}</div>
            </div>
          </div>
        </div>

        <section class="community-section">
          <div class="community-heading"><div><p>Collector ledger</p><h2>Ownership History</h2></div><span v-if="community?.ownership" class="community-owner">{{ community.ownership.ownerLabel }}</span></div>
          <p v-if="communityError" class="community-error">{{ communityError }}</p>
          <template v-if="!community?.ownership">
            <p class="community-copy">This verified card is not yet bound to a collector account.</p>
            <form v-if="isCustomerSignedIn" class="community-form" @submit.prevent="claimCard"><label>Visibility<select v-model="claimForm.visibility"><option value="public">Show my collector name</option><option value="anonymous">Show as private collector</option><option value="private">Do not show ownership</option></select></label><label>Binding note<textarea v-model="claimForm.note" maxlength="1000" /></label><button class="community-button" :disabled="savingCommunity">Bind card</button></form>
            <button v-else class="community-button" type="button" @click="signInForCommunity">Sign in to bind this card</button>
          </template>
          <form v-else-if="isCurrentOwner" class="community-form transfer-form" @submit.prevent="transferCard"><h3>Transfer this certificate</h3><label>Recipient account email<input v-model="transferForm.recipientEmail" type="email" required /></label><label>New owner visibility<select v-model="transferForm.visibility"><option value="public">Show collector name</option><option value="anonymous">Show as private collector</option><option value="private">Do not show ownership</option></select></label><label>Transfer note<textarea v-model="transferForm.message" maxlength="1000" /></label><button class="community-button" :disabled="savingCommunity">Record transfer</button></form>

          <div v-if="community?.timeline.length" class="community-list"><h3>Transfer history</h3><div v-for="event in community.timeline" :key="event.id" class="community-item"><strong>{{ event.eventTypeCode === 'transferred' ? `${event.fromLabel} transferred to ${event.toLabel}` : `${event.toLabel} bound this card` }}</strong><p v-if="event.message">{{ event.message }}</p><span>{{ new Date(event.createdAt).toLocaleString() }}</span></div></div>
        </section>
      </div>
    </div>

    <div v-else-if="hasLoaded" class="error-container">
      <div class="error-icon">🔍</div>
      <h1 class="error-title">Card Not Found</h1>
      <p class="error-message">
        The certificate ID you entered does not exist in our database.<br />
        Please check the number and try again.
      </p>
      <div class="error-actions">
        <router-link to="/verify" class="btn-primary">Try Another ID</router-link>
        <router-link to="/" class="btn-secondary">Return to Home</router-link>
      </div>
    </div>
  </main>

  <div v-if="isAiOpen" class="ai-modal-backdrop" @click.self="closeAiInfo">
    <section class="ai-modal">
      <div class="ai-modal-head">
        <div>
          <p>AI Character Info</p>
          <h2>{{ displayTitle }}</h2>
        </div>
        <button type="button" @click="closeAiInfo">Close</button>
      </div>

      <label class="ai-language">
        <span>Language</span>
        <select v-model="aiLanguage" @change="openAiInfo">
          <option value="en">English</option>
          <option value="zh-CN">中文</option>
          <option value="ja">Japanese</option>
          <option value="ko">Korean</option>
          <option value="fr">French</option>
          <option value="de">German</option>
          <option value="es">Spanish</option>
          <option value="it">Italian</option>
          <option value="pt">Portuguese</option>
        </select>
      </label>

      <div v-if="isAiLoading" class="ai-loading">Loading cached AI context...</div>
      <div v-else-if="aiError" class="ai-error">{{ aiError }}</div>
      <div v-else class="ai-character-info" v-html="aiHtml" />
    </section>
  </div>

  <LegacySiteFooter />
</template>

<style scoped>
.ai-modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 50;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(5, 10, 22, 0.58);
}

.ai-modal {
  width: min(720px, 100%);
  max-height: calc(100vh - 48px);
  overflow: auto;
  border-radius: 18px;
  background: #fff;
  padding: 24px;
  color: #111827;
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.26);
}

.ai-modal-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
}

.ai-modal-head p,
.ai-modal-head h2 {
  margin: 0;
}

.ai-modal-head p {
  margin-bottom: 6px;
  color: #55647f;
  font-size: 12px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  font-weight: 700;
}

.ai-modal-head button,
.ai-language select {
  height: 42px;
  border-radius: 12px;
  border: 1px solid rgba(20, 32, 51, 0.12);
  background: #fff;
  font: inherit;
}

.ai-modal-head button {
  padding: 0 14px;
  cursor: pointer;
}

.ai-language {
  display: block;
  margin-top: 18px;
}

.ai-language span {
  display: block;
  margin-bottom: 8px;
  font-weight: 700;
}

.ai-language select {
  width: 220px;
  padding: 0 12px;
}

.ai-loading,
.ai-error,
.ai-character-info {
  margin-top: 18px;
  line-height: 1.7;
}

.ai-error {
  color: #a11f2b;
}

.community-section {
  margin-top: 28px;
  border-top: 1px solid #2a2a2a;
  padding-top: 24px;
}

.community-heading,
.community-item,
.community-form label {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.community-heading {
  align-items: flex-start;
}

.community-heading p,
.community-item p,
.community-item span,
.community-copy {
  color: #999;
}

.community-heading p {
  margin: 0 0 4px;
  font-size: 12px;
  text-transform: uppercase;
}

.community-heading h2,
.community-list h3,
.transfer-form h3 {
  margin: 0;
  font-size: 17px;
}

.community-owner {
  border: 1px solid #333;
  color: #ddd;
  padding: 6px 9px;
  font-size: 12px;
}

.community-form {
  display: grid;
  gap: 10px;
  margin-top: 14px;
}

.community-form label {
  flex-direction: column;
  color: #bbb;
  font-size: 12px;
  font-weight: 700;
}

.community-form input,
.community-form textarea,
.community-form select {
  width: 100%;
  border: 1px solid #333;
  background: #181818;
  color: #fff;
  padding: 10px;
  font: inherit;
}

.community-form textarea {
  min-height: 70px;
  resize: vertical;
}

.community-button {
  justify-self: start;
  border: 1px solid #e94560;
  background: #e94560;
  color: #fff;
  padding: 10px 14px;
  font-weight: 700;
  cursor: pointer;
}

.community-button.secondary {
  border-color: #444;
  background: transparent;
}

.community-error {
  color: #ff8ea0;
  margin: 12px 0;
}

.community-list {
  margin-top: 22px;
}

.community-list {
  display: grid;
  gap: 9px;
}

.community-item {
  display: block;
  border: 1px solid #292929;
  padding: 12px;
}

.community-item strong,
.community-item p,
.community-item span {
  display: block;
}

.community-item p {
  margin: 6px 0;
  line-height: 1.5;
}

.community-item span {
  font-size: 12px;
}

@media (max-width: 720px) {
  .ai-modal-head {
    flex-direction: column;
  }

  .ai-language select {
    width: 100%;
  }
}
</style>
