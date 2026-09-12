import assert from 'node:assert/strict'
import test from 'node:test'
import { ScanCache, fingerprintFile, SCAN_ALGORITHM_VERSION, type CacheStore } from '../src/lib/scan-cache.ts'

class MemoryStore implements CacheStore {
  values = new Map<string, unknown>()
  async get(key: string) { return structuredClone(this.values.get(key)) }
  async set(key: string, value: unknown) { this.values.set(key, structuredClone(value)) }
  async delete(key: string) { this.values.delete(key) }
  async entries() { return [...this.values.entries()] }
}
const found = { certIds: ['0012345678'], qrTexts: ['nxrgrading.com/card/0012345678'] }
const empty = { certIds: [], qrTexts: [] }

test('cache reuses complete evidence across renames but changes with bytes even at identical size and mtime', async () => {
  const first = new File(['aaaa'], 'one.jpg', { lastModified: 10 })
  const renamed = new File(['aaaa'], 'two.jpg', { lastModified: 20 })
  const changed = new File(['bbbb'], 'one.jpg', { lastModified: 10 })
  const hash = await fingerprintFile(first), store = new MemoryStore(), cache = new ScanCache(store)
  assert.equal(await fingerprintFile(renamed), hash)
  assert.notEqual(await fingerprintFile(changed), hash)
  await cache.write(hash, first.size, 'standard', found)
  assert.deepEqual(await cache.read(hash, first.size, 'standard'), found)
  assert.equal(await cache.read(await fingerprintFile(changed), changed.size, 'standard'), undefined)
  assert.equal(await cache.read(hash, first.size + 1, 'standard'), undefined)
  assert.equal(await cache.read(hash, first.size, 'deep'), undefined)
})

test('negative results and conflicts are reusable, errors and incomplete evidence are not', async () => {
  const store = new MemoryStore(), cache = new ScanCache(store), hash = 'a'.repeat(64)
  await cache.write(hash, 100, 'standard', empty)
  assert.deepEqual(await cache.read(hash, 100, 'standard'), empty)
  await cache.write(hash, 100, 'deep', { ...found, error: 'incomplete conflict verification' })
  assert.equal(await cache.read(hash, 100, 'deep'), undefined)
  await cache.write(hash, 100, 'deep', { certIds: ['0012345678', '2'], qrTexts: [...found.qrTexts, '/card/2'] })
  assert.deepEqual((await cache.read(hash, 100, 'deep'))?.certIds, ['0012345678', '2'])
  await cache.forget(hash)
  assert.equal(store.values.size, 0)
})

test('corrupt checksums, certificate/text mismatches and algorithm versions fail closed', async () => {
  const store = new MemoryStore(), cache = new ScanCache(store), hash = 'b'.repeat(64)
  await cache.write(hash, 100, 'standard', found)
  const key = `scan:${SCAN_ALGORITHM_VERSION}:${hash}:standard`
  const original = structuredClone(store.values.get(key)) as any
  for (const mutate of [
    (x: any) => { x.result.certIds = ['999'] },
    (x: any) => { x.result = { certIds: ['999'], qrTexts: ['/card/999'] } },
    (x: any) => { x.hash = 'c'.repeat(64) },
    (x: any) => { x.version = 'older-reader' },
    (x: any) => { x.savedAt = Date.now() + 100_000 },
  ]) {
    const record = structuredClone(original); mutate(record); store.values.set(key, record)
    assert.equal(await cache.read(hash, 100, 'standard'), undefined)
  }
})

test('quota/unavailable storage never blocks scanning and old records expire', async () => {
  const broken: CacheStore = { get: async () => { throw new Error('unavailable') }, set: async () => { throw new Error('quota') }, delete: async () => {}, entries: async () => [] }
  const cache = new ScanCache(broken), hash = 'd'.repeat(64)
  assert.equal(await cache.read(hash, 100, 'standard'), undefined)
  await cache.write(hash, 100, 'standard', found)
  const store = new MemoryStore(); let now = 1_000
  const timed = new ScanCache(store, () => now)
  await timed.write(hash, 100, 'standard', found)
  now += 31 * 24 * 3600 * 1000
  assert.equal(await timed.read(hash, 100, 'standard'), undefined)
  await timed.prune(); assert.equal(store.values.size, 0)
})
