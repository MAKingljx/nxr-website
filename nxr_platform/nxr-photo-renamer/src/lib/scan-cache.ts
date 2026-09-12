import { createStore, get, set, del, entries } from 'idb-keyval'
import { parseCertificateLink } from './pairing'
import type { ScanResult } from './scanner'
import type { ScanMode } from './scan-policy'

// Bump whenever accepted scan evidence or its interpretation changes.
export const SCAN_ALGORITHM_VERSION = 'gold-regions-v2'
const MAX_RECORDS = 24_000
const MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000
const HASH = /^[a-f0-9]{64}$/
export interface CacheStore {
  get(key: string): Promise<unknown>
  set(key: string, value: unknown): Promise<void>
  delete(key: string): Promise<void>
  entries(): Promise<[string, unknown][]>
}
let database: ReturnType<typeof createStore> | undefined
const db = () => database ??= createStore('nxr-photo-scan-cache', 'evidence')
const browserStore: CacheStore = {
  get: key => get(key, db()), set: (key, value) => set(key, value, db()),
  delete: key => del(key, db()), entries: () => entries(db()) as Promise<[string, unknown][]>,
}

export async function fingerprintFile(file: File): Promise<string | undefined> {
  if (file.size <= 0 || file.size > 40 * 1024 * 1024) return undefined
  try { return digest(await file.arrayBuffer()) } catch { return undefined }
}
export async function digest(value: BufferSource | string): Promise<string> {
  const buffer = typeof value === 'string' ? new TextEncoder().encode(value) : value
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', buffer)), byte => byte.toString(16).padStart(2, '0')).join('')
}

interface EvidenceRecord {
  version: string; hash: string; size: number; mode: ScanMode; savedAt: number
  result: ScanResult; checksum: string
}
function validResult(value: unknown): value is ScanResult {
  if (!value || typeof value !== 'object') return false
  const result = value as ScanResult
  if (result.error !== undefined || !Array.isArray(result.certIds) || !Array.isArray(result.qrTexts)
    || result.qrTexts.length > 16 || result.certIds.length > 16
    || !result.qrTexts.every(text => typeof text === 'string' && text.length > 0 && text.length <= 2048 && text.trim() === text)) return false
  const expected = [...new Set(result.qrTexts.map(parseCertificateLink).filter((id): id is string => id !== null))]
  return JSON.stringify(result.certIds) === JSON.stringify(expected)
}
export class ScanCache {
  constructor(private readonly store: CacheStore = browserStore, private readonly now = Date.now) {}
  private key(hash: string, mode: ScanMode) { return `scan:${SCAN_ALGORITHM_VERSION}:${hash}:${mode}` }
  async forget(hash: string | undefined): Promise<void> {
    if (!hash || !HASH.test(hash)) return
    try { await Promise.all(['standard', 'deep'].map(mode => this.store.delete(this.key(hash, mode as ScanMode)))) } catch { /* Optional persistence. */ }
  }
  async read(hash: string | undefined, size: number, mode: ScanMode): Promise<ScanResult | undefined> {
    if (!hash || !HASH.test(hash)) return undefined
    try {
      const record = await this.store.get(this.key(hash, mode)) as EvidenceRecord | undefined
      if (!record || record.version !== SCAN_ALGORITHM_VERSION || record.hash !== hash || record.size !== size
        || record.mode !== mode || !Number.isFinite(record.savedAt) || record.savedAt > this.now()
        || this.now() - record.savedAt > MAX_AGE_MS || !validResult(record.result)) return undefined
      const { checksum, ...body } = record
      if (checksum !== await digest(JSON.stringify(body))) return undefined
      return structuredClone(record.result)
    } catch { return undefined }
  }
  async write(hash: string | undefined, size: number, mode: ScanMode, result: ScanResult): Promise<void> {
    if (!hash || !HASH.test(hash) || !Number.isSafeInteger(size) || size <= 0 || !validResult(result)) return
    try {
      const body = { version: SCAN_ALGORITHM_VERSION, hash, size, mode, savedAt: this.now(), result: structuredClone(result) }
      await this.store.set(this.key(hash, mode), { ...body, checksum: await digest(JSON.stringify(body)) })
    } catch { /* Cache availability must never block the actual scanner. */ }
  }
  async readSession(key: string): Promise<unknown> {
    if (!HASH.test(key)) return undefined
    try {
      const record = await this.store.get(`session:${SCAN_ALGORITHM_VERSION}:${key}`) as { value: unknown; savedAt: number; checksum: string } | undefined
      if (!record || !Number.isFinite(record.savedAt) || record.savedAt > this.now() || this.now() - record.savedAt > MAX_AGE_MS) return undefined
      const { checksum, ...body } = record
      return checksum === await digest(JSON.stringify(body)) ? record.value : undefined
    } catch { return undefined }
  }
  async writeSession(key: string, value: unknown): Promise<void> {
    if (!HASH.test(key)) return
    try {
      const body = { savedAt: this.now(), value }
      await this.store.set(`session:${SCAN_ALGORITHM_VERSION}:${key}`, { ...body, checksum: await digest(JSON.stringify(body)) })
    } catch { /* Optional persistence. */ }
  }
  async prune(): Promise<void> {
    try {
      const records = (await this.store.entries()).map(([key, value]) => ({ key, savedAt: (value as { savedAt?: number })?.savedAt ?? 0 }))
        .sort((a, b) => b.savedAt - a.savedAt)
      for (const [index, record] of records.entries()) {
        if (index >= MAX_RECORDS || this.now() - record.savedAt > MAX_AGE_MS
          || !record.key.includes(`:${SCAN_ALGORITHM_VERSION}:`)) await this.store.delete(record.key)
      }
    } catch { /* A quota or storage error falls back to uncached work. */ }
  }
}

export const scanCache = new ScanCache()
