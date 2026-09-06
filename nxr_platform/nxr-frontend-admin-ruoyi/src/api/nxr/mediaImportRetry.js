const RETRYABLE_HTTP_STATUSES = new Set([
  0,
  408,
  409,
  423,
  425,
  429,
  500,
  502,
  503,
  504
])

export const MEDIA_IMPORT_MAX_RETRIES = 3
export const MEDIA_IMPORT_MAX_ATTEMPTS = MEDIA_IMPORT_MAX_RETRIES + 1
export const MEDIA_IMPORT_MAX_RETRY_DELAY_MS = 8000

const DEFAULT_RETRY_DELAYS_MS = [1000, 2000, 4000]

function numericStatus(value) {
  const status = Number(value)
  return Number.isInteger(status) ? status : null
}

function errorPayload(error) {
  const payload = error?.response?.data
  if (!payload || typeof payload !== 'object') return {}
  if (payload.data && typeof payload.data === 'object') {
    return { ...payload.data, ...payload }
  }
  return payload
}

function errorStatus(error, payload) {
  const responseStatus = numericStatus(error?.response?.status)
  const payloadStatus = numericStatus(payload?.status ?? payload?.code)
  if (payloadStatus !== null && payloadStatus >= 400 && (responseStatus === null || responseStatus < 400)) {
    return payloadStatus
  }
  return responseStatus ?? numericStatus(error?.status ?? error?.statusCode)
}

function normalizedRetryDelay(value, fallback, retriesUsed) {
  const delay = Number(value)
  const selected = Number.isFinite(delay) && delay >= 0
    ? delay * (2 ** retriesUsed)
    : fallback
  return Math.min(Math.round(selected), MEDIA_IMPORT_MAX_RETRY_DELAY_MS)
}

function isNetworkFailure(error) {
  if (!error || typeof error !== 'object' || error.response) return false
  const code = String(error.code || '').toUpperCase()
  const message = String(error.message || '')
  return error.isAxiosError === true
    || ['ERR_NETWORK', 'ECONNABORTED', 'ETIMEDOUT', 'ECONNRESET'].includes(code)
    || message === 'Network Error'
    || message.toLowerCase().includes('timeout')
}

export function getMediaImportRetryPlan(error, retriesUsed, options = {}) {
  const maxRetries = options.maxRetries ?? MEDIA_IMPORT_MAX_RETRIES
  const retryDelaysMs = options.retryDelaysMs ?? DEFAULT_RETRY_DELAYS_MS
  const payload = errorPayload(error)
  const status = errorStatus(error, payload)
  const fallbackDelay = retryDelaysMs[Math.min(retriesUsed, retryDelaysMs.length - 1)]
    ?? MEDIA_IMPORT_MAX_RETRY_DELAY_MS

  if (retriesUsed >= maxRetries || status === 422) {
    return { retryable: false, delayMs: 0, status }
  }

  let retryable
  if (typeof payload.retryable === 'boolean') {
    retryable = payload.retryable
  } else if (status === null) {
    retryable = isNetworkFailure(error)
  } else {
    retryable = RETRYABLE_HTTP_STATUSES.has(status)
  }

  return {
    retryable,
    delayMs: retryable
      ? normalizedRetryDelay(
          payload.retry_after_ms ?? payload.retryAfterMs,
          fallbackDelay,
          retriesUsed
        )
      : 0,
    status
  }
}

function wait(delayMs) {
  return new Promise(resolve => setTimeout(resolve, delayMs))
}

export async function executeWithMediaImportRetry(operation, options = {}) {
  const maxRetries = options.maxRetries ?? MEDIA_IMPORT_MAX_RETRIES
  const sleep = options.sleep ?? wait
  let attempt = 1

  while (true) {
    try {
      return await operation({ attempt, maxAttempts: maxRetries + 1 })
    } catch (error) {
      const retriesUsed = attempt - 1
      const plan = getMediaImportRetryPlan(error, retriesUsed, {
        maxRetries,
        retryDelaysMs: options.retryDelaysMs
      })
      if (!plan.retryable) {
        await options.onFailure?.({
          error,
          status: plan.status,
          failedAttempt: attempt,
          retriesUsed,
          maxAttempts: maxRetries + 1,
          exhausted: retriesUsed >= maxRetries
        })
        throw error
      }

      const nextAttempt = attempt + 1
      await options.onRetry?.({
        error,
        status: plan.status,
        delayMs: plan.delayMs,
        retryCount: retriesUsed + 1,
        failedAttempt: attempt,
        nextAttempt,
        maxAttempts: maxRetries + 1
      })
      await sleep(plan.delayMs)
      attempt = nextAttempt
    }
  }
}

export async function executeMediaImportBatches(batches, sendBatch, options = {}) {
  const results = []

  for (let batchIndex = 0; batchIndex < batches.length; batchIndex += 1) {
    const batch = batches[batchIndex]
    const result = await executeWithMediaImportRetry(
      attemptContext => sendBatch(batch, {
        ...attemptContext,
        batchIndex: batchIndex + 1,
        batchCount: batches.length
      }),
      {
        ...options,
        onRetry: retry => options.onRetry?.({
          ...retry,
          batch,
          batchIndex: batchIndex + 1,
          batchCount: batches.length
        }),
        onFailure: failure => options.onFailure?.({
          ...failure,
          batch,
          batchIndex: batchIndex + 1,
          batchCount: batches.length,
          completedBatches: batchIndex
        })
      }
    )
    results.push(result)
    await options.onBatchSuccess?.(result, {
      batch,
      batchIndex: batchIndex + 1,
      batchCount: batches.length,
      completedBatches: batchIndex + 1
    })
  }

  return results
}

export function createMediaImportProgress(totalBytes) {
  const normalizedTotal = Math.max(Number(totalBytes) || 0, 0)
  let highestLoadedBytes = 0

  return {
    update(completedBytes, batchLoadedBytes = 0) {
      const candidate = Math.min(
        normalizedTotal,
        Math.max(Number(completedBytes) || 0, 0) + Math.max(Number(batchLoadedBytes) || 0, 0)
      )
      highestLoadedBytes = Math.max(highestLoadedBytes, candidate)
      const calculatedPercent = normalizedTotal
        ? Math.round((highestLoadedBytes / normalizedTotal) * 100)
        : 0
      return {
        percent: Math.min(calculatedPercent, 99),
        loadedBytes: highestLoadedBytes,
        totalBytes: normalizedTotal
      }
    },
    finish() {
      highestLoadedBytes = normalizedTotal
      return {
        percent: 100,
        loadedBytes: normalizedTotal,
        totalBytes: normalizedTotal
      }
    }
  }
}

export function mergeUniqueMediaImportValues(existing, incoming) {
  return Array.from(new Set([...(existing || []), ...(incoming || [])]))
}

export function enrichMediaImportError(error, aggregate, failure, totalBatches) {
  const enrichedError = error && typeof error === 'object'
    ? error
    : new Error(typeof error === 'string' ? error : 'Folder import failed')
  const failedBatch = failure?.batchIndex ?? Math.min((failure?.completedBatches ?? 0) + 1, totalBatches)
  const completedBatches = failure?.completedBatches ?? Math.max(failedBatch - 1, 0)
  const importSummary = {
    ...aggregate,
    missingCertIds: [...(aggregate.missingCertIds || [])],
    invalidNames: [...(aggregate.invalidNames || [])],
    duplicateNames: [...(aggregate.duplicateNames || [])],
    updatedSubmissionIds: [...(aggregate.updatedSubmissionIds || [])],
    completedBatches,
    successfulBatches: completedBatches,
    totalBatches,
    failedBatch
  }

  enrichedError.importSummary = importSummary
  enrichedError.failedBatch = failedBatch
  enrichedError.completedBatches = completedBatches
  enrichedError.totalBatches = totalBatches
  enrichedError.failedAttempt = failure?.failedAttempt ?? null
  enrichedError.maxAttempts = failure?.maxAttempts ?? MEDIA_IMPORT_MAX_ATTEMPTS
  enrichedError.retryStatus = failure?.status ?? null
  return enrichedError
}
