import assert from 'node:assert/strict'
import test from 'node:test'
import { ScanCache, type CacheStore } from '../src/lib/scan-cache.ts'
import { sessionKey, saveScanSession, restoreScanSession } from '../src/lib/scan-session.ts'
import { suggestPairs } from '../src/lib/pairing.ts'
import { createManualPair } from '../src/lib/manual-pairing.ts'
import type { Photo } from '../src/lib/types.ts'

function fixture(): Photo[] {
  return Array.from({ length: 6 }, (_, index) => ({ id: String(index), name: `${index}.jpg`,
    file: new File(['fixture'], `${index}.jpg`), thumbnailUrl: '', contentHash: String(index).repeat(64),
    scanState: index === 1 || index === 3 ? 'found' : 'none',
    certIds: index === 1 || index === 3 ? [`000${index}`] : [],
    qrTexts: index === 1 || index === 3 ? [`/card/000${index}`] : [],
  }))
}
function memoryCache() {
  const values = new Map<string, unknown>()
  const store: CacheStore = { get: async key => values.get(key), set: async (key, value) => { values.set(key, structuredClone(value)) },
    delete: async key => { values.delete(key) }, entries: async () => [...values.entries()] }
  return new ScanCache(store)
}
test('resume preserves deselection, omitted groups and validated manual entry with an exact file-set fingerprint', async () => {
  const photos = fixture(), allNames = photos.map(p => p.name), automatic = suggestPairs(photos), cache = memoryCache()
  automatic[0]!.selected = false
  const pairs = [automatic[0]!, createManualPair(photos, [automatic[0]!], '5', '0009', allNames)]
  const key = await sessionKey(photos)
  await saveScanSession(key, photos, pairs, true, cache)
  const resumed = await restoreScanSession(key, photos, allNames, cache)
  assert.equal(resumed.interrupted, true)
  assert.deepEqual(resumed.pairs.map(p => [p.backId, p.certId, p.manual ?? false, p.selected]), [
    ['1', '0001', false, false], ['5', '0009', true, true],
  ])
  photos[0]!.contentHash = 'f'.repeat(64)
  assert.notEqual(await sessionKey(photos), key)
  assert.equal((await restoreScanSession(await sessionKey(photos), photos, allNames, cache)).interrupted, false)
})

test('saved manual decisions are revalidated against QR evidence and unavailable fingerprints cannot resume', async () => {
  const photos = fixture(), cache = memoryCache(), names = photos.map(p => p.name), key = await sessionKey(photos)
  await saveScanSession(key, photos, [createManualPair(photos, [], '5', '9', names)], false, cache)
  photos[5]!.scanState = 'found'; photos[5]!.certIds = ['8']; photos[5]!.qrTexts = ['/card/8']
  const result = await restoreScanSession(key, photos, names, cache)
  assert.ok(!result.pairs.some(pair => pair.manual && pair.certId === '9'))
  photos[0]!.contentHash = undefined
  assert.equal(await sessionKey(photos), undefined)
})
