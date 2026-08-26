export const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? (import.meta.env.DEV ? 'http://127.0.0.1:8088' : '')).replace(/\/$/, '')
const defaultAdminBaseUrl = 'https://nxrgrading.com/x7k9m2q4r8v6c3p1'

function resolveAdminBaseUrl() {
  const configuredValue = import.meta.env.VITE_ADMIN_BASE_URL?.trim()

  if (!configuredValue) {
    return defaultAdminBaseUrl
  }

  try {
    return new URL(configuredValue, window.location.origin).toString()
  } catch {
    return defaultAdminBaseUrl
  }
}

export const adminBaseUrl = resolveAdminBaseUrl()

export type PlatformHealth = {
  service: string
  status: string
  version: string
}

export type PlatformSummary = {
  platform: string
  phase: string
  publicAdminEntry: string
  modules: string[]
  publishedCount: number
  submissionCount: number
}

export type FeaturedCard = {
  certId: string
  productType?: ProductType | 'label_product' | null
  vintageClassification?: string | null
  merchDescription?: string | null
  cardCategory: string
  cardCategoryLabel: string
  cardName: string
  brandName: string
  yearLabel: string
  languageCode: string
  setName: string
  finalGradeValue: number | null
  finalGradeLabel: string | null
  frontImageUrl: string
}

export type PublicOverview = {
  platformName: string
  headline: string
  subheadline: string
  publishedCertificates: number
  pendingReview: number
  waitlistCount: number
  featuredCards: FeaturedCard[]
}

export type ProductType = 'graded_card' | 'merch_product' | 'vintage_product'

export type PublicCardDetail = {
  certId: string
  verificationSlug: string
  qrUrl: string
  publishedAt: string
  cardCategory: string
  cardCategoryLabel: string
  productType?: ProductType | 'label_product' | null
  vintageClassification?: string | null
  merchDescription?: string | null
  cardName: string
  movieName: string | null
  releaseYear: string | null
  productionCompany: string | null
  filmType: string | null
  sportsType: string | null
  groupName: string | null
  yearLabel: string
  brandName: string
  playerName: string
  varietyName: string
  languageCode: string
  setName: string
  cardNumber: string
  populationValue: number
  centeringScore: number | null
  edgesScore: number | null
  cornersScore: number | null
  surfaceScore: number | null
  finalGradeValue: number | null
  finalGradeLabel: string | null
  decisionMethodCode: string | null
  decisionNotes: string | null
  frontImageUrl: string
  backImageUrl: string
}

export type WaitlistCountResponse = {
  count: number
}

export type WaitlistSignupResponse = {
  status: 'ok'
  email: string
  count: number
  alreadyJoined: boolean
}

export type AiCharacterInfoResponse = {
  certId: string
  brand: string
  character: string
  language: string
  html: string
  provider: string
  cached: boolean
  generatedAt: string
}

async function readJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, init)
  if (!response.ok) {
    const errorMessage = response.status === 404 ? 'Record not found' : await readErrorMessage(response)
    throw new Error(errorMessage)
  }
  return response.json() as Promise<T>
}

async function readErrorMessage(response: Response) {
  try {
    const payload = await response.json()
    return payload.message || payload.error || `Request failed with ${response.status}`
  } catch {
    return `Request failed with ${response.status}`
  }
}

export function fetchHealth() {
  return readJson<PlatformHealth>('/api/platform/health')
}

export function fetchSummary() {
  return readJson<PlatformSummary>('/api/platform/summary')
}

export function fetchOverview() {
  return readJson<PublicOverview>('/api/public/overview')
}

export function fetchPublicCard(certId: string) {
  return readJson<PublicCardDetail>(`/api/public/cards/${encodeURIComponent(certId)}`)
}

export function fetchWaitlistCount() {
  return readJson<WaitlistCountResponse>('/api/public/waitlist-count')
}

export function joinWaitlist(email: string) {
  return readJson<WaitlistSignupResponse>('/api/public/waitlist', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email }),
  })
}

export function fetchAiCharacterInfo(payload: { certId: string; brand: string; character: string; language: string }) {
  return readJson<AiCharacterInfoResponse>('/api/public/ai-character-info', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}
