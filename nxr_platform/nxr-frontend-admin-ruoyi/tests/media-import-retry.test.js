import assert from 'node:assert/strict'
import test from 'node:test'

import {
  createMediaImportProgress,
  enrichMediaImportError,
  executeMediaImportBatches,
  getMediaImportRetryPlan,
  mergeUniqueMediaImportValues
} from '../src/api/nxr/mediaImportRetry.js'

function httpError(status, data = {}) {
  const error = new Error(`HTTP ${status}`)
  error.response = { status, data }
  return error
}

test('retry classification honors server guidance and permanent validation failures', () => {
  for (const status of [0, 408, 409, 423, 425, 429, 500, 502, 503, 504]) {
    assert.equal(getMediaImportRetryPlan(httpError(status), 0).retryable, true)
  }

  assert.equal(getMediaImportRetryPlan(new Error('Network Error'), 0).retryable, true)
  assert.equal(getMediaImportRetryPlan(new Error('local validation failed'), 0).retryable, false)
  assert.equal(getMediaImportRetryPlan('The session expired', 0).retryable, false)
  assert.equal(getMediaImportRetryPlan(httpError(200, { code: 401 }), 0).retryable, false)
  assert.equal(getMediaImportRetryPlan(httpError(400), 0).retryable, false)
  assert.equal(getMediaImportRetryPlan(httpError(422, { retryable: true }), 0).retryable, false)
  assert.equal(getMediaImportRetryPlan(httpError(503, { retryable: false }), 0).retryable, false)
  assert.equal(getMediaImportRetryPlan(httpError(400, { retryable: true }), 0).retryable, true)
})

test('retry delay uses 1/2/4 second backoff, server override, and the 8 second cap', () => {
  assert.equal(getMediaImportRetryPlan(httpError(503), 0).delayMs, 1000)
  assert.equal(getMediaImportRetryPlan(httpError(503), 1).delayMs, 2000)
  assert.equal(getMediaImportRetryPlan(httpError(503), 2).delayMs, 4000)
  assert.equal(getMediaImportRetryPlan(httpError(503, { retry_after_ms: 1500 }), 0).delayMs, 1500)
  assert.equal(getMediaImportRetryPlan(httpError(503, { retry_after_ms: 1500 }), 1).delayMs, 3000)
  assert.equal(getMediaImportRetryPlan(httpError(503, { retry_after_ms: 1500 }), 2).delayMs, 6000)
  assert.equal(getMediaImportRetryPlan(httpError(503, { retry_after_ms: 5000 }), 1).delayMs, 8000)
  assert.equal(getMediaImportRetryPlan(httpError(503), 3).retryable, false)
})

test('application error payload keeps server retry metadata when HTTP status is 200', () => {
  const error = httpError(200, {
    code: 503,
    data: { retryable: true, retry_after_ms: 2500 }
  })
  assert.deepEqual(
    getMediaImportRetryPlan(error, 0),
    { retryable: true, delayMs: 2500, status: 503 }
  )
})

test('a batch receives at most the initial attempt plus three retries', async () => {
  const attempts = []
  const sleeps = []
  let terminalFailure

  await assert.rejects(
    executeMediaImportBatches(
      ['always-fails'],
      async (_batch, context) => {
        attempts.push(context.attempt)
        throw httpError(503)
      },
      {
        sleep: async delayMs => sleeps.push(delayMs),
        onFailure: failure => {
          terminalFailure = failure
        }
      }
    ),
    /HTTP 503/
  )

  assert.deepEqual(attempts, [1, 2, 3, 4])
  assert.deepEqual(sleeps, [1000, 2000, 4000])
  assert.equal(terminalFailure.failedAttempt, 4)
  assert.equal(terminalFailure.exhausted, true)
  assert.equal(terminalFailure.status, 503)
  assert.equal(terminalFailure.batchIndex, 1)
  assert.equal(terminalFailure.completedBatches, 0)
})

test('successful batches are not resent when a later batch retries', async () => {
  const calls = []
  const sleeps = []
  const completed = []
  const retries = []
  let secondBatchFailures = 0

  const results = await executeMediaImportBatches(
    ['batch-1', 'batch-2'],
    async (batch, context) => {
      calls.push([batch, context.attempt])
      if (batch === 'batch-2' && secondBatchFailures < 2) {
        secondBatchFailures += 1
        throw httpError(503)
      }
      return { batch }
    },
    {
      sleep: async delayMs => sleeps.push(delayMs),
      onRetry: retry => retries.push([retry.batchIndex, retry.retryCount]),
      onBatchSuccess: result => completed.push(result.batch)
    }
  )

  assert.deepEqual(results, [{ batch: 'batch-1' }, { batch: 'batch-2' }])
  assert.deepEqual(calls, [
    ['batch-1', 1],
    ['batch-2', 1],
    ['batch-2', 2],
    ['batch-2', 3]
  ])
  assert.deepEqual(sleeps, [1000, 2000])
  assert.deepEqual(retries, [[2, 1], [2, 2]])
  assert.deepEqual(completed, ['batch-1', 'batch-2'])
})

test('permanent failure stops without sending later batches', async () => {
  const calls = []

  await assert.rejects(
    executeMediaImportBatches(
      ['invalid', 'must-not-send'],
      async batch => {
        calls.push(batch)
        throw httpError(422, { retryable: true })
      },
      { sleep: async () => assert.fail('permanent error must not sleep') }
    ),
    /HTTP 422/
  )

  assert.deepEqual(calls, ['invalid'])
})

test('progress stays monotonic and never reaches 100 before explicit completion', () => {
  const progress = createMediaImportProgress(100)
  const firstAttempt = progress.update(0, 100)
  const retryStarted = progress.update(0, 0)
  const nextBatch = progress.update(50, 25)
  const finished = progress.finish()

  assert.deepEqual(firstAttempt, { percent: 99, loadedBytes: 100, totalBytes: 100 })
  assert.deepEqual(retryStarted, firstAttempt)
  assert.deepEqual(nextBatch, firstAttempt)
  assert.deepEqual(finished, { percent: 100, loadedBytes: 100, totalBytes: 100 })
})

test('successful certificate and diagnostic lists merge without duplicates', () => {
  assert.deepEqual(
    mergeUniqueMediaImportValues([11, 12], [12, 13, 11]),
    [11, 12, 13]
  )
})

test('terminal failure carries the completed aggregate and failed batch', () => {
  const original = httpError(503)
  const enriched = enrichMediaImportError(
    original,
    {
      matchedEntries: 2,
      savedFiles: 3,
      updatedSides: 3,
      missingCertIds: ['MISSING'],
      invalidNames: [],
      duplicateNames: [],
      updatedSubmissionIds: [11, 12]
    },
    {
      batchIndex: 3,
      completedBatches: 2,
      failedAttempt: 4,
      maxAttempts: 4,
      status: 503
    },
    5
  )

  assert.equal(enriched, original)
  assert.equal(enriched.failedBatch, 3)
  assert.equal(enriched.completedBatches, 2)
  assert.equal(enriched.failedAttempt, 4)
  assert.equal(enriched.retryStatus, 503)
  assert.deepEqual(enriched.importSummary, {
    matchedEntries: 2,
    savedFiles: 3,
    updatedSides: 3,
    missingCertIds: ['MISSING'],
    invalidNames: [],
    duplicateNames: [],
    updatedSubmissionIds: [11, 12],
    completedBatches: 2,
    successfulBatches: 2,
    totalBatches: 5,
    failedBatch: 3
  })
})
