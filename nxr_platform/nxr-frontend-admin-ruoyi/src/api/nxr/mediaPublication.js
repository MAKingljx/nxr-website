export const MEDIA_PUBLICATION_BATCH_SIZE = 5
export const MEDIA_PUBLICATION_MAX_ATTEMPTS = 3
const TRANSIENT = new Set([408, 425, 429, 500, 502, 503, 504, 524])
const VERSION = /^[a-f0-9]{64}$/
const wait = ms => new Promise(resolve => setTimeout(resolve, ms))

export function publicationSelection(submissionIds, versions) {
  const ids = [...new Set(submissionIds)]
  if (!ids.length || ids.some(id => !Number.isSafeInteger(id) || id <= 0 || !VERSION.test(versions?.[id] || ''))) {
    throw new Error('Reload the image queue and select the cards again before publishing.')
  }
  return { ids, versions: Object.fromEntries(ids.map(id => [id, versions[id]])) }
}

function retryable(error) {
  if (error?.publicationContractError) return false
  const status = Number(error?.status || error?.response?.data?.code || error?.response?.status)
  if (status) return TRANSIENT.has(status)
  return ['ERR_NETWORK', 'ECONNABORTED', 'ETIMEDOUT', 'ECONNRESET'].includes(String(error?.code || '').toUpperCase())
    || /network|timeout|timed out/i.test(String(error?.message || ''))
}

function verifiedResult(response, ids, versions) {
  const result = response?.data ?? response
  const published = Array.isArray(result?.published) ? result.published : []
  const failures = Array.isArray(result?.failures) ? result.failures : []
  const found = new Set()
  let valid = result?.requestedCount === ids.length && Array.isArray(result?.published) && Array.isArray(result?.failures)
    && result.publishedCount === published.length && result.failedCount === failures.length
    && published.length + failures.length === ids.length
  for (const row of [...published, ...failures]) {
    if (!row || !ids.includes(row.submissionId) || found.has(row.submissionId)) valid = false
    if (row) found.add(row.submissionId)
  }
  for (const row of published) if (!row || row.stagedVersion !== versions[row.submissionId]) valid = false
  if (!valid) {
    const error = new Error('Publication could not be confirmed. Reload the image queue to check the result.')
    error.publicationContractError = true
    throw error
  }
  return result
}

// Short requests stay below proxy timeouts. Each retry carries the original
// immutable selection, so a concurrent import can never become this operation.
export async function executeMediaPublicationBatches(submissionIds, expectedVersions, operation, options = {}) {
  const selection = publicationSelection(submissionIds, expectedVersions)
  const sleep = options.sleep || wait
  const result = { requestedCount: selection.ids.length, publishedCount: 0, failedCount: 0, processedCount: 0,
    published: [], failures: [], unknownIds: [], pendingIds: [...selection.ids] }
  const snapshot = () => ({ ...result, published: [...result.published], failures: [...result.failures],
    unknownIds: [...result.unknownIds], pendingIds: [...result.pendingIds] })
  const batchCount = Math.ceil(selection.ids.length / MEDIA_PUBLICATION_BATCH_SIZE)
  for (let offset = 0; offset < selection.ids.length; offset += MEDIA_PUBLICATION_BATCH_SIZE) {
    if (options.shouldContinue && !options.shouldContinue()) {
      const error = new Error('Publication stopped before the remaining cards were sent.')
      error.publishSummary = snapshot()
      throw error
    }
    const ids = selection.ids.slice(offset, offset + MEDIA_PUBLICATION_BATCH_SIZE)
    const versions = Object.fromEntries(ids.map(id => [id, selection.versions[id]]))
    const batchIndex = offset / MEDIA_PUBLICATION_BATCH_SIZE + 1
    let current
    for (let attempt = 1; attempt <= MEDIA_PUBLICATION_MAX_ATTEMPTS; attempt++) {
      options.onProgress?.(snapshot(), { phase: 'publishing', batchIndex, batchCount, attempt })
      try {
        current = verifiedResult(await operation([...ids], { ...versions }), ids, versions)
        break
      } catch (error) {
        if (attempt < MEDIA_PUBLICATION_MAX_ATTEMPTS && retryable(error)) {
          options.onProgress?.(snapshot(), { phase: 'retrying', batchIndex, batchCount, attempt })
          await sleep(attempt * 1000)
          continue
        }
        result.unknownIds = ids
        result.pendingIds = selection.ids.slice(offset + ids.length)
        error.publishSummary = snapshot()
        throw error
      }
    }
    result.published.push(...current.published)
    result.failures.push(...current.failures)
    result.publishedCount += current.publishedCount
    result.failedCount += current.failedCount
    result.processedCount += ids.length
    result.pendingIds = selection.ids.slice(offset + ids.length)
    options.onProgress?.(snapshot(), { phase: 'batch-complete', batchIndex, batchCount })
  }
  return snapshot()
}
