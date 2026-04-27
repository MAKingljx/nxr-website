const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? 'http://127.0.0.1:8088'

function buildUrl(path: string) {
  return `${apiBaseUrl}${path}`
}

async function readJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(buildUrl(path), {
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers ?? {}),
    },
    ...init,
  })

  if (!response.ok) {
    const text = await response.text()
    throw new Error(text || `Request failed with ${response.status}`)
  }

  return response.json() as Promise<T>
}

export type AdminSession = {
  accessToken: string
  username: string
  displayName: string
  roleCode: string
  loggedInAt: string
}

export type DashboardMetric = {
  certId: string
  cardName: string
  brandName: string
  finalGradeValue: number
  finalGradeLabel: string
}

export type AdminDashboard = {
  totalSubmissions: number
  pendingReview: number
  approvedReady: number
  publishedCertificates: number
  waitlistCount: number
  recentPublished: DashboardMetric[]
}

export type SubmissionListItem = {
  id: number
  certId: string
  cardName: string
  brandName: string
  yearLabel: string
  languageCode: string
  statusCode: string
  createdAt: string
  updatedAt: string
  finalGradeValue: number
  finalGradeLabel: string
}

export type SubmissionMediaItem = {
  mediaSideCode: string
  mediaStageCode: string
  publicUrl: string
}

export type SubmissionDetail = {
  id: number
  certId: string
  cardName: string
  yearLabel: string
  brandName: string
  playerName: string
  varietyName: string
  setName: string
  cardNumber: string
  languageCode: string
  populationValue: number
  statusCode: string
  gradingPhaseCode: string
  entryNotes: string
  createdAt: string
  updatedAt: string
  approvedAt: string | null
  publishedAt: string | null
  centeringScore: number
  edgesScore: number
  cornersScore: number
  surfaceScore: number
  finalGradeValue: number
  finalGradeLabel: string
  aiGradeValue: number | null
  aiConfidenceValue: number | null
  decisionMethodCode: string
  decisionNotes: string
  media: SubmissionMediaItem[]
}

export type SubmissionListResponse = {
  items: SubmissionListItem[]
  page: number
  pageSize: number
  total: number
}

export type MediaQueueItem = {
  submissionId: number
  certId: string
  cardName: string
  statusCode: string
  approvedAt: string | null
  publishedAt: string | null
  finalGradeValue: number
  finalGradeLabel: string
  stagedFrontUrl: string | null
  stagedBackUrl: string | null
  publishedFrontUrl: string | null
  publishedBackUrl: string | null
  hasStagedFront: boolean
  hasStagedBack: boolean
  hasPublishedFront: boolean
  hasPublishedBack: boolean
  readyToPublish: boolean
}

export type MediaQueueSummary = {
  trackedEntries: number
  readyToPublish: number
  livePublished: number
  missingMedia: number
}

export type MediaQueueResponse = {
  items: MediaQueueItem[]
  summary: MediaQueueSummary
}

export type MediaImportResponse = {
  matchedEntries: number
  savedFiles: number
  updatedSides: number
  missingCertIds: string[]
  invalidNames: string[]
  duplicateNames: string[]
  updatedSubmissionIds: number[]
}

export type MediaPublishResponse = {
  submissionId: number
  certId: string
  statusCode: string
  publishedAt: string
  publishedFrontUrl: string
  publishedBackUrl: string
}

export type CreateSubmissionPayload = {
  certId: string
  cardName: string
  yearLabel: string
  brandName: string
  playerName: string
  varietyName: string
  setName: string
  cardNumber: string
  languageCode: string
  populationValue: number
  centeringScore: number
  edgesScore: number
  cornersScore: number
  surfaceScore: number
  entryNotes: string
}

export function loginAdmin(username: string, password: string) {
  return readJson<AdminSession>('/api/admin/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  })
}

export function fetchDashboard() {
  return readJson<AdminDashboard>('/api/admin/dashboard')
}

export function fetchSubmissions(params: { page?: number; pageSize?: number; status?: string; query?: string }) {
  const searchParams = new URLSearchParams()

  if (params.page) searchParams.set('page', String(params.page))
  if (params.pageSize) searchParams.set('pageSize', String(params.pageSize))
  if (params.status) searchParams.set('status', params.status)
  if (params.query) searchParams.set('query', params.query)

  const queryString = searchParams.toString()
  return readJson<SubmissionListResponse>(`/api/admin/submissions${queryString ? `?${queryString}` : ''}`)
}

export function fetchSubmission(submissionId: number) {
  return readJson<SubmissionDetail>(`/api/admin/submissions/${submissionId}`)
}

export function createSubmission(payload: CreateSubmissionPayload) {
  return readJson<SubmissionDetail>('/api/admin/submissions', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function fetchMediaQueue(query?: string) {
  const searchParams = new URLSearchParams()

  if (query) {
    searchParams.set('query', query)
  }

  const queryString = searchParams.toString()
  return readJson<MediaQueueResponse>(`/api/admin/media/queue${queryString ? `?${queryString}` : ''}`)
}

export function importMediaFolder(
  files: File[],
  onProgress?: (percent: number, loaded: number, total: number) => void,
) {
  return new Promise<MediaImportResponse>((resolve, reject) => {
    const formData = new FormData()

    for (const file of files) {
      formData.append('image_files', file)
    }

    const xhr = new XMLHttpRequest()
    xhr.open('POST', buildUrl('/api/admin/media/import-folder'))
    xhr.responseType = 'json'
    xhr.timeout = 1000 * 60 * 30

    xhr.upload.addEventListener('progress', (event) => {
      if (!event.lengthComputable) return
      const percent = Math.min(100, Math.round((event.loaded / event.total) * 100))
      onProgress?.(percent, event.loaded, event.total)
    })

    xhr.onload = () => {
      let payload: unknown = null

      if (xhr.response && typeof xhr.response === 'object') {
        payload = xhr.response
      } else if (xhr.responseText) {
        try {
          payload = JSON.parse(xhr.responseText)
        } catch {
          payload = { message: xhr.responseText.trim() }
        }
      }

      if (xhr.status >= 200 && xhr.status < 300 && payload) {
        resolve(payload as MediaImportResponse)
        return
      }

      const responsePayload = payload as { message?: string; error?: string } | null
      reject(new Error(responsePayload?.message ?? responsePayload?.error ?? `Request failed with ${xhr.status}`))
    }

    xhr.onerror = () => {
      reject(new Error('Folder import request failed. The connection was interrupted or the server closed the request.'))
    }

    xhr.onabort = () => {
      reject(new Error('Folder import was aborted before the server returned a result.'))
    }

    xhr.ontimeout = () => {
      reject(new Error('Folder import timed out before the server returned a result.'))
    }

    xhr.send(formData)
  })
}

export function publishSubmissionMedia(submissionId: number) {
  return readJson<MediaPublishResponse>(`/api/admin/media/submissions/${submissionId}/publish`, {
    method: 'POST',
  })
}
