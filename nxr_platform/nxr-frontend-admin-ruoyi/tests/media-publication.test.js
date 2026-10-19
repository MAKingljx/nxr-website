import assert from 'node:assert/strict'
import test from 'node:test'
import { executeMediaPublicationBatches, publicationSelection } from '../src/api/nxr/mediaPublication.js'

const token = id => id.toString(16).padStart(64, '0')
const selection = count => {
  const ids = Array.from({ length: count }, (_, index) => index + 1)
  return [ids, Object.fromEntries(ids.map(id => [id, token(id)]))]
}
const success = (ids, versions) => ({ data: { requestedCount: ids.length, publishedCount: ids.length,
  failedCount: 0, published: ids.map(submissionId => ({ submissionId, stagedVersion: versions[submissionId] })), failures: [] } })

test('publishes in sequential bounded requests and reports confirmed progress', async () => {
  const [ids, versions] = selection(12), requests = [], progress = []
  let inFlight = 0
  const result = await executeMediaPublicationBatches(ids, versions, async (batch, frozen) => {
    assert.equal(++inFlight, 1)
    requests.push([...batch])
    await Promise.resolve()
    inFlight--
    return success(batch, frozen)
  }, { onProgress: (value, meta) => { if (meta.phase === 'batch-complete') progress.push(value.processedCount) } })
  assert.deepEqual(requests.map(batch => batch.length), [5, 5, 2])
  assert.deepEqual(progress, [5, 10, 12])
  assert.equal(result.publishedCount, 12)
  assert.deepEqual(result.unknownIds, [])
  assert.deepEqual(result.pendingIds, [])
})

test('network retries preserve the selected image version even if inputs or request objects change', async () => {
  const [ids, versions] = selection(2), attempts = [], delays = []
  const result = await executeMediaPublicationBatches(ids, versions, async (batch, sent) => {
    attempts.push({ batch: [...batch], versions: { ...sent } })
    if (attempts.length === 1) {
      ids.push(3); versions[1] = token(100); batch.push(99); sent[1] = token(200)
      throw Object.assign(new Error('timeout'), { code: 'ECONNABORTED' })
    }
    return success(batch, sent)
  }, { sleep: async delay => delays.push(delay) })
  assert.deepEqual(attempts[1], attempts[0])
  assert.deepEqual(delays, [1000])
  assert.equal(result.requestedCount, 2)
  assert.equal(result.publishedCount, 2)
})

test('per-card failure preserves successes and continues the remaining selected cards', async () => {
  const [ids, versions] = selection(6)
  const result = await executeMediaPublicationBatches(ids, versions, async (batch, sent) => {
    const response = success(batch, sent)
    if (batch.includes(3)) {
      response.data.published = response.data.published.filter(row => row.submissionId !== 3)
      response.data.publishedCount--
      response.data.failedCount++
      response.data.failures.push({ submissionId: 3, message: 'The images changed. Reload before publishing.' })
    }
    return response
  })
  assert.equal(result.publishedCount, 5)
  assert.equal(result.failedCount, 1)
  assert.equal(result.processedCount, 6)
  assert.deepEqual(result.failures.map(row => row.submissionId), [3])
})

test('ambiguous failure stops later batches and keeps confirmed, unknown and unsent cards distinct', async () => {
  const [ids, versions] = selection(12), requests = []
  await assert.rejects(executeMediaPublicationBatches(ids, versions, async (batch, sent) => {
    requests.push([...batch])
    if (batch[0] === 1) return success(batch, sent)
    throw Object.assign(new Error('connection lost'), { response: { status: 524 } })
  }, { sleep: async () => {} }), error => {
    assert.equal(requests.length, 4)
    assert.equal(error.publishSummary.publishedCount, 5)
    assert.equal(error.publishSummary.processedCount, 5)
    assert.deepEqual(error.publishSummary.unknownIds, [6, 7, 8, 9, 10])
    assert.deepEqual(error.publishSummary.pendingIds, [11, 12])
    return true
  })
})

for (const status of [400, 401, 403, 409, 422]) {
  test(`does not retry permanent HTTP ${status}`, async () => {
    const [ids, versions] = selection(1)
    let requests = 0
    await assert.rejects(executeMediaPublicationBatches(ids, versions, async () => {
      requests++
      throw Object.assign(new Error('request rejected'), { response: { status } })
    }), error => {
      assert.equal(requests, 1)
      assert.deepEqual(error.publishSummary.unknownIds, [1])
      return true
    })
  })
}

test('application error codes are considered even when the HTTP response was 200', async () => {
  const [ids, versions] = selection(1)
  let requests = 0
  await executeMediaPublicationBatches(ids, versions, async (batch, sent) => {
    if (++requests === 1) throw Object.assign(new Error('backend unavailable'), { status: 500, response: { status: 200 } })
    return success(batch, sent)
  }, { sleep: async () => {} })
  assert.equal(requests, 2)
})

for (const corrupt of [
  result => { result.published = {} },
  result => { result.failures = 'invalid' },
  result => { result.published[0] = null },
  result => { result.published[0].stagedVersion = token(999) },
  result => { delete result.published[0].stagedVersion },
  result => { result.published[1].submissionId = result.published[0].submissionId },
  result => { result.published[0].submissionId = 999 },
  result => { result.requestedCount = 1 }
]) {
  test('does not confirm or retry malformed, duplicate or wrong-version results', async () => {
    const [ids, versions] = selection(6)
    let requests = 0
    await assert.rejects(executeMediaPublicationBatches(ids, versions, async (batch, sent) => {
      requests++
      const response = success(batch, sent)
      corrupt(response.data)
      return response
    }), error => {
      assert.equal(error.publicationContractError, true)
      assert.equal(requests, 1)
      assert.deepEqual(error.publishSummary.unknownIds, [1, 2, 3, 4, 5])
      assert.deepEqual(error.publishSummary.pendingIds, [6])
      assert.equal(error.publishSummary.publishedCount, 0)
      return true
    })
  })
}

test('leaving the page lets the current request settle and stops unsent batches', async () => {
  const [ids, versions] = selection(6)
  let active = true, requests = 0
  await assert.rejects(executeMediaPublicationBatches(ids, versions, async (batch, sent) => {
    requests++; active = false
    return success(batch, sent)
  }, { shouldContinue: () => active }), error => {
    assert.equal(requests, 1)
    assert.equal(error.publishSummary.publishedCount, 5)
    assert.deepEqual(error.publishSummary.unknownIds, [])
    assert.deepEqual(error.publishSummary.pendingIds, [6])
    return true
  })
})

test('invalid or absent versions fail before any request and duplicate selections are deduplicated', () => {
  assert.throws(() => publicationSelection([1], {}))
  assert.throws(() => publicationSelection([0], { 0: token(0) }))
  assert.throws(() => publicationSelection([], {}))
  assert.deepEqual(publicationSelection([1, 1], { 1: token(1) }), { ids: [1], versions: { 1: token(1) } })
})
