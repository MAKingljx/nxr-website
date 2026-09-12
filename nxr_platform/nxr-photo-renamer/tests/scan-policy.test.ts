import assert from 'node:assert/strict'
import test from 'node:test'
import { deepScanCandidates, nextDeepScanBatch, mergeDeepScanPairs, mergeScanEvidence, SCAN_LIMITS } from '../src/lib/scan-policy.ts'
import { suggestPairs } from '../src/lib/pairing.ts'
import type { Photo } from '../src/lib/types.ts'

function photo(id: string, certId?: string): Photo {
  return { id, name: `${id}.png`, file: new File(['fixture'], `${id}.png`), thumbnailUrl: '',
    scanState: certId ? 'found' : 'none', certIds: certId ? [certId] : [], qrTexts: [] }
}

test('deep waves fill fourteen lanes without scanning adjacent possible fronts unnecessarily', () => {
  const photos = Array.from({ length: 28 }, (_, index) => photo(String(index + 1)))
  const first = nextDeepScanBatch(photos, [], new Set(), 14)
  assert.deepEqual(first.map(p => p.id), Array.from({ length: 14 }, (_, index) => String(28 - index * 2)))
  const attempted = new Set(first.map(p => p.id))
  for (const back of first) Object.assign(back, photo(back.id, `7${back.id.padStart(9, '0')}`))
  const pairs = suggestPairs(photos)
  assert.equal(pairs.length, 14)
  assert.deepEqual(nextDeepScanBatch(photos, pairs, attempted, 14), [])
})

test('a failed back candidate leaves its deferred preceding photo eligible for the next wave', () => {
  const photos = Array.from({ length: 6 }, (_, index) => photo(String(index + 1)))
  const first = nextDeepScanBatch(photos, [], new Set(), 14)
  assert.deepEqual(first.map(p => p.id), ['6', '4', '2'])
  const attempted = new Set(first.map(p => p.id))
  const second = nextDeepScanBatch(photos, [], attempted, 14)
  assert.deepEqual(second.map(p => p.id), ['5', '3', '1'])
  second.forEach(p => attempted.add(p.id))
  assert.deepEqual(nextDeepScanBatch(photos, [], attempted, 14), [])
})

test('deep retry excludes both sides of existing pairs, valid codes and ambiguous codes', () => {
  const photos = [photo('1'), photo('2', '7123456789'), photo('3'), photo('4'), photo('5', '8123456789')]
  photos[3].scanState = 'error'
  photos[4].scanState = 'ambiguous'
  const pairs = suggestPairs(photos).slice(0, 1)
  pairs[0].selected = false
  assert.deepEqual(deepScanCandidates(photos, pairs).map(p => p.id), ['3', '4'])
})

test('deep retry adds a newly decoded back and preserves the deselected original pair', () => {
  const photos = [photo('1'), photo('2', '7123456789'), photo('3'), photo('4')]
  const previous = suggestPairs(photos)
  previous[0].selected = false
  photos[3] = photo('4', '8123456789')
  const merged = mergeDeepScanPairs(photos, previous, new Set(['3', '4']))
  assert.equal(merged.length, 2)
  assert.equal(merged[0], previous[0])
  assert.equal(merged[0].selected, false)
  assert.equal(merged[1].frontId, '3')
  assert.equal(merged[1].backId, '4')
})

test('deep retry cannot resurrect a removed result or steal an existing pair photo', () => {
  const photos = [photo('1'), photo('2', '7123456789'), photo('3', '8123456789'), photo('4'), photo('5', '9123456789')]
  const firstPair = suggestPairs(photos).slice(0, 1)
  assert.deepEqual(mergeDeepScanPairs(photos, firstPair, new Set(['3', '4'])), firstPair)
})

test('a failed or cancelled deep retry retains every existing pair', () => {
  const photos = [photo('1'), photo('2', '7123456789'), photo('3'), photo('4')]
  const previous = suggestPairs(photos)
  photos[2].scanState = 'pending'
  photos[3].scanState = 'error'
  assert.deepEqual(mergeDeepScanPairs(photos, previous, new Set(['3', '4'])), previous)
})

test('timeouts reserve delivery time and deep retry receives a larger worker budget', () => {
  assert.ok(SCAN_LIMITS.deep.workerBudgetMs > SCAN_LIMITS.standard.workerBudgetMs)
  for (const limit of Object.values(SCAN_LIMITS)) assert.ok(limit.timeoutMs > limit.workerBudgetMs)
})

test('automatic retry retains conflicting codes from both passes', () => {
  const result = mergeScanEvidence(
    { certIds: ['00123'], qrTexts: ['/card/00123'], error: '校验未完成' },
    { certIds: ['00456'], qrTexts: ['/card/00456'] },
  )
  assert.deepEqual(result.certIds, ['00123', '00456'])
  assert.equal(result.error, undefined)
})

test('empty retry cannot silently approve an incomplete first pass', () => {
  const result = mergeScanEvidence(
    { certIds: ['00123'], qrTexts: ['/card/00123'], error: '校验未完成' },
    { certIds: [], qrTexts: [] },
  )
  assert.equal(result.error, '校验未完成')
  const confirmed = mergeScanEvidence(result, { certIds: ['00123'], qrTexts: ['/card/00123'] })
  assert.equal(confirmed.error, undefined)
  assert.deepEqual(confirmed.certIds, ['00123'])
})
