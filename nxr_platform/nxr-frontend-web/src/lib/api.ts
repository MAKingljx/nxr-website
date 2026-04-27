const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? 'http://127.0.0.1:8088'

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
  cardName: string
  brandName: string
  yearLabel: string
  languageCode: string
  setName: string
  finalGradeValue: number
  finalGradeLabel: string
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

export type PublicCardDetail = {
  certId: string
  verificationSlug: string
  qrUrl: string
  publishedAt: string
  cardName: string
  yearLabel: string
  brandName: string
  playerName: string
  varietyName: string
  languageCode: string
  setName: string
  cardNumber: string
  populationValue: number
  centeringScore: number
  edgesScore: number
  cornersScore: number
  surfaceScore: number
  finalGradeValue: number
  finalGradeLabel: string
  decisionMethodCode: string
  decisionNotes: string
  frontImageUrl: string
  backImageUrl: string
}

async function readJson<T>(path: string): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`)
  if (!response.ok) {
    const errorMessage = response.status === 404 ? 'Record not found' : `Request failed with ${response.status}`
    throw new Error(errorMessage)
  }
  return response.json() as Promise<T>
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
