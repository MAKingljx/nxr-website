import type {
  ApiErrorBody,
  CreatedRecord,
  EditableFeedbackRecord,
  EditableRequestRecord,
  FetchClient,
  RecordListResponse,
  RecordResponse,
} from './types'

export class AnonymousApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(message: string, status = 0, code = 'request_failed') {
    super(message)
    this.name = 'AnonymousApiError'
    this.status = status
    this.code = code
  }
}

export function defaultFetchClient(input: string, init?: RequestInit): Promise<Response> {
  if (typeof globalThis.fetch !== 'function') {
    throw new AnonymousApiError('当前环境无法连接反馈服务，请稍后再试。')
  }
  return globalThis.fetch(input, init)
}

function recordPath(kind: 'requests' | 'feedbacks', id?: string) {
  const base = `/api/${kind}`
  return id ? `${base}/${encodeURIComponent(id.trim())}` : base
}

async function readJson<T>(response: Response): Promise<T> {
  let body: T | ApiErrorBody | undefined
  try {
    body = await response.json() as T | ApiErrorBody
  } catch {
    if (!response.ok) {
      throw new AnonymousApiError('反馈服务暂时不可用，请稍后再试。', response.status)
    }
    throw new AnonymousApiError('反馈服务返回了无法识别的内容。', response.status, 'invalid_response')
  }

  if (!response.ok) {
    const errorBody = body as ApiErrorBody
    throw new AnonymousApiError(
      errorBody.error?.message || errorBody.message || '操作失败，请检查编号和编辑凭证。',
      response.status,
      errorBody.error?.code,
    )
  }
  return body as T
}

async function send<T>(fetchClient: FetchClient, path: string, init: RequestInit): Promise<T> {
  try {
    return await readJson<T>(await fetchClient(path, init))
  } catch (error) {
    if (error instanceof AnonymousApiError) throw error
    throw new AnonymousApiError('无法连接反馈服务，静态组件清单仍可正常浏览。')
  }
}

function jsonHeaders(extra: Record<string, string> = {}) {
  return { 'Content-Type': 'application/json', Accept: 'application/json', ...extra }
}

export function createAnonymousRecordClient(fetchClient: FetchClient = defaultFetchClient) {
  return {
    listRequests() {
      return send<RecordListResponse<EditableRequestRecord>>(fetchClient, recordPath('requests'), {
        method: 'GET',
        headers: { Accept: 'application/json' },
      })
    },
    createRequest(payload: Omit<EditableRequestRecord, 'id' | 'status'>, idempotencyKey: string, editToken = '') {
      return send<CreatedRecord<EditableRequestRecord>>(fetchClient, recordPath('requests'), {
        method: 'POST',
        headers: jsonHeaders({ 'Idempotency-Key': idempotencyKey, ...(editToken ? { 'X-Edit-Token': editToken } : {}) }),
        body: JSON.stringify(payload),
      })
    },
    loadRequest(id: string) {
      return send<RecordResponse<EditableRequestRecord>>(fetchClient, recordPath('requests', id), {
        method: 'GET',
        headers: { Accept: 'application/json' },
      })
    },
    updateRequest(id: string, editToken: string, payload: Omit<EditableRequestRecord, 'id' | 'status'>) {
      return send<RecordResponse<EditableRequestRecord>>(fetchClient, recordPath('requests', id), {
        method: 'PATCH',
        headers: jsonHeaders({ 'X-Edit-Token': editToken }),
        body: JSON.stringify(payload),
      })
    },
    createFeedback(payload: Omit<EditableFeedbackRecord, 'id' | 'status'>, idempotencyKey: string, editToken = '') {
      return send<CreatedRecord<EditableFeedbackRecord>>(fetchClient, recordPath('feedbacks'), {
        method: 'POST',
        headers: jsonHeaders({ 'Idempotency-Key': idempotencyKey, ...(editToken ? { 'X-Edit-Token': editToken } : {}) }),
        body: JSON.stringify(payload),
      })
    },
    listFeedbacks() {
      return send<RecordListResponse<EditableFeedbackRecord>>(fetchClient, recordPath('feedbacks'), {
        method: 'GET',
        headers: { Accept: 'application/json' },
      })
    },
    loadFeedback(id: string) {
      return send<RecordResponse<EditableFeedbackRecord>>(fetchClient, recordPath('feedbacks', id), {
        method: 'GET',
        headers: { Accept: 'application/json' },
      })
    },
    updateFeedback(id: string, editToken: string, payload: Omit<EditableFeedbackRecord, 'id' | 'status'>) {
      return send<RecordResponse<EditableFeedbackRecord>>(fetchClient, recordPath('feedbacks', id), {
        method: 'PATCH',
        headers: jsonHeaders({ 'X-Edit-Token': editToken }),
        body: JSON.stringify(payload),
      })
    },
  }
}

export function createIdempotencyKey(prefix: 'request' | 'feedback') {
  const randomPart = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `${prefix}-${randomPart}`
}

export function createEditToken() {
  if (!globalThis.crypto?.getRandomValues) return ''
  const bytes = globalThis.crypto.getRandomValues(new Uint8Array(32))
  let binary = ''
  for (const value of bytes) binary += String.fromCharCode(value)
  return globalThis.btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/u, '')
}
