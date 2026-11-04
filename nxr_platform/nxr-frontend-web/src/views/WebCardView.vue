<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import { fetchPublicCard, streamAiCharacterInfo, type ProductType, type PublicCardDetail } from '../lib/api'
import {
  aiProviderNotice,
  pythonAiCopy,
  pythonGradeText,
  pythonLanguageLabel,
  pythonVideoAction,
} from '../lib/cardDisplayParity'

const props = defineProps<{ certId: string }>()

const card = ref<PublicCardDetail | null>(null)
const isLoading = ref(false)
const hasLoaded = ref(false)
const isAiOpen = ref(false)
const isAiLoading = ref(false)
const aiHtml = ref('')
const aiStreamingText = ref('')
const aiError = ref('')
const aiLanguage = ref('en')
const aiProvider = ref('')
const aiCached = ref(false)
const placeholderImage = `${import.meta.env.BASE_URL}static/placeholder.png`

const productType = computed<ProductType>(() => {
  const value = card.value?.productType
  if (value === 'merch_product' || value === 'label_product') return 'merch_product'
  return value === 'vintage_product' ? value : 'graded_card'
})

const productProfile = computed(() => {
  const profiles = {
    graded_card: {
      label: 'Graded Card',
      pageVariant: 'graded-card',
      showFinalGrade: true,
      showSubgrades: true,
      showClassification: false,
      verificationText: 'Authenticated and graded by NXR.',
    },
    merch_product: {
      label: 'Merch Product',
      pageVariant: 'merch-product',
      showFinalGrade: false,
      showSubgrades: false,
      showClassification: false,
      verificationText: 'Authenticated by NXR.',
    },
    vintage_product: {
      label: 'Vintage Card',
      pageVariant: 'vintage-product',
      showFinalGrade: false,
      showSubgrades: false,
      showClassification: true,
      verificationText: 'Authenticated and classified by NXR.',
    },
  }
  return profiles[productType.value]
})

const gradeText = computed(() => {
  return card.value ? pythonGradeText(card.value) : ''
})

const aiCopy = computed(() => pythonAiCopy(card.value?.cardCategory))
const aiNotice = computed(() => aiProviderNotice(aiProvider.value, aiCached.value))
const videoAction = computed(() => (card.value ? pythonVideoAction(card.value) : null))

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
  const rows: Array<{ key: string; value: string | number | null | undefined }> = [
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
    if (productType.value !== 'vintage_product') {
      rows.push({ key: 'Year', value: detail.yearLabel })
    }
    rows.push(
      { key: 'Brand', value: detail.brandName },
      { key: 'Set', value: detail.setName },
      { key: 'Card number', value: detail.cardNumber },
      { key: 'Language', value: pythonLanguageLabel(detail.languageCode) },
    )

    if (detail.cardCategory === 'sports_card') {
      rows.push({ key: 'Sports type', value: detail.sportsType })
    }

    if (detail.cardCategory === 'celebrity_card') {
      rows.push({ key: 'Group name', value: detail.groupName })
    }
  }

  rows.push({ key: 'Population', value: detail.populationValue })
  return rows.filter((row) => row.value !== null && row.value !== undefined && String(row.value).trim() !== '')
})

const subgrades = computed(() => {
  if (!card.value) {
    return []
  }

  return [
    {
      name: 'Centering',
      score: card.value.centeringScore,
      description: 'Measures how evenly the image is positioned within the borders. Critical for vintage cards.',
    },
    {
      name: 'Edges',
      score: card.value.edgesScore,
      description: 'Evaluates the condition of all four edges. Look for chipping, whitening, or wear.',
    },
    {
      name: 'Corners',
      score: card.value.cornersScore,
      description: 'Assesses sharpness and wear on all four corners. The most common area for damage.',
    },
    {
      name: 'Surface',
      score: card.value.surfaceScore,
      description: 'Checks for scratches, print defects, stains, or other surface imperfections.',
    },
  ].filter((item): item is { name: string; score: number; description: string } => item.score !== null)
})

const vintageClassifications = [
  {
    code: 'I',
    name: 'Pristine',
    description: 'Near-pristine vintage survivor. Almost no age-related patina. Sharp corners, crisp edges and clean surface. Free of creases, scuffs, stains and paper loss.',
  },
  {
    code: 'II',
    name: 'Nova',
    description: 'Displays natural age patina matching its era. Minor soft corner rounding and trivial edge wear. No creases or tears. Card surface stays fully intact.',
  },
  {
    code: 'III',
    name: 'Legacy',
    description: 'Noticeable corner rounding and edge wear with moderate age patina. Faint soft indentations may appear. No hard creases and no paper loss.',
  },
  {
    code: 'IV',
    name: 'Helix',
    description: 'Carries honest traces of past collector enjoyment. Heavy corner and edge wear, together with rich age patina. Visible creases, surface scuffs and minor spots are acceptable. The card remains structurally complete, without major tears or missing pieces.',
  },
]

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
  } catch {
    card.value = null
  } finally {
    isLoading.value = false
    hasLoaded.value = true
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
  aiStreamingText.value = ''
  aiProvider.value = ''
  aiCached.value = false

  try {
    const response = await streamAiCharacterInfo(
      {
        certId: card.value.certId,
        brand: card.value.brandName || card.value.productionCompany || 'Unknown',
        character: displayTitle.value,
        language: aiLanguage.value,
      },
      (chunk) => {
        aiStreamingText.value += chunk
      },
    )
    aiHtml.value = response.html
    aiProvider.value = response.provider
    aiCached.value = response.cached
    aiStreamingText.value = ''
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

  <main class="card-page" :class="card ? `card-page--${productProfile.pageVariant}` : ''">
    <div v-if="isLoading" class="error-container">
      <div class="error-icon">🔍</div>
      <h1 class="error-title">Loading Card</h1>
      <p class="error-message">Loading certificate data from the NXR platform.</p>
    </div>

    <div v-else-if="card" class="card-layout">
      <div class="images-col">
        <figure class="img-wrap">
          <img :src="card.frontImageUrl || placeholderImage" alt="Front" loading="eager" @error="useFallbackImage" />
          <figcaption class="img-label">Front</figcaption>
        </figure>
        <figure class="img-wrap">
          <img :src="card.backImageUrl || placeholderImage" alt="Back" loading="lazy" @error="useFallbackImage" />
          <figcaption class="img-label">Back</figcaption>
        </figure>

        <div class="ai-character-section">
          <button class="ai-character-btn" type="button" @click="openAiInfo">
            <span class="ai-icon">🤖</span>
            <span class="ai-text">{{ aiCopy.button }}</span>
            <span class="ai-subtext">{{ aiCopy.subtitle }}</span>
          </button>
          <p class="ai-note">Powered by DeepSeek AI · Available in 9 languages</p>
          <a v-if="videoAction" :href="videoAction.href" target="_blank" rel="noopener" class="ai-character-btn yt-btn">
            <span class="ai-icon">▶</span>
            <span class="ai-text">{{ videoAction.label }}</span>
            <span class="ai-subtext">{{ videoAction.subtitle }}</span>
          </a>
        </div>

        <div class="verification-info">
          <div class="verification-title">Verification</div>
          <p>{{ productProfile.verificationText }}</p>
          <div class="verification-id">{{ card.certId }}</div>
        </div>
      </div>

      <div class="card-info">
        <div class="product-type-label">{{ productProfile.label }}</div>
        <h1 class="card-title">{{ displayTitle }}</h1>
        <div class="card-cert">Certificate ID: {{ card.certId }}</div>
        <div
          v-if="productProfile.showClassification && (card.yearLabel || card.vintageClassification)"
          class="vintage-classification"
        >
          <span v-if="card.yearLabel">{{ card.yearLabel }}</span>
          <strong v-if="card.vintageClassification">{{ card.vintageClassification }}</strong>
        </div>
        <div v-if="productProfile.showFinalGrade" class="grade-badge">Final Grade: {{ gradeText }}</div>

        <section v-if="productProfile.showClassification" class="vintage-condition-guide">
          <h2>NXR Vintage Card Classifications</h2>
          <p class="vintage-condition-intro">Four archival classifications for vintage cards. Age-related patina is regarded as authentic character, not damage.</p>
          <div class="vintage-condition-list">
            <article v-for="classification in vintageClassifications" :key="classification.code" class="vintage-condition-item">
              <div class="vintage-condition-code">{{ classification.code }}</div>
              <div>
                <div class="vintage-condition-name">{{ classification.name }}</div>
                <p>{{ classification.description }}</p>
              </div>
            </article>
          </div>
        </section>

        <div class="detail-grid">
          <div v-for="row in detailRows" :key="row.key" class="detail-row">
            <span class="detail-key">{{ row.key }}</span>
            <span class="detail-val">{{ row.value }}</span>
          </div>
        </div>

        <section v-if="productType === 'merch_product' && card.merchDescription" class="merch-description-section">
          <h2>About This Merch</h2>
          <p>{{ card.merchDescription }}</p>
        </section>

        <section v-if="productProfile.showSubgrades" class="subgrades-section">
          <div class="subgrades-header">
            <h2 class="subgrades-title">Sub-Grades Breakdown</h2>
            <p>Four critical components of the grade</p>
          </div>
          <div class="subgrades-grid">
            <div v-for="item in subgrades" :key="item.name" class="subgrade-card">
              <div class="subgrade-copy">
                <div class="subgrade-name">{{ item.name }}</div>
                <p class="subgrade-description">{{ item.description }}</p>
              </div>
              <div class="subgrade-score" :class="subgradeClass(item.score)">
                {{ Number(item.score).toFixed(1) }}
              </div>
            </div>
          </div>
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
          <p>{{ aiCopy.modal }}</p>
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

      <div v-if="isAiLoading && !aiStreamingText" class="ai-loading">Loading AI context...</div>
      <div v-if="aiStreamingText" class="ai-character-info ai-character-info--streaming">{{ aiStreamingText }}</div>
      <div v-else-if="aiError" class="ai-error">{{ aiError }}</div>
      <div v-else-if="!isAiLoading" class="ai-character-info" v-html="aiHtml" />
      <p v-if="!isAiLoading && aiNotice" class="ai-provider-note">{{ aiNotice }}</p>
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
  border-radius: 8px;
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
  letter-spacing: 0;
  text-transform: uppercase;
  font-weight: 700;
}

.ai-modal-head button,
.ai-language select {
  height: 42px;
  border-radius: 8px;
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

.ai-character-info--streaming {
  white-space: pre-wrap;
}

.ai-icon,
.ai-subtext,
.ai-note {
  display: block;
}

.ai-icon {
  margin-bottom: 8px;
  font-size: 24px;
}

.ai-subtext,
.ai-note,
.ai-provider-note,
.subgrades-header p {
  color: #94a3b8;
  font-size: 13px;
}

.ai-note {
  margin: 8px 0 12px;
  text-align: center;
}

.yt-btn {
  margin-top: 12px;
  background: #b91c1c;
}

.subgrades-header {
  margin-bottom: 14px;
}

.subgrades-header p {
  margin: 4px 0 0;
}

.merch-description-section {
  margin-top: 24px;
}

.merch-description-section h2 {
  margin-bottom: 8px;
}

.ai-provider-note {
  margin-top: 14px;
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
