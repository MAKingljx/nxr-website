import type { Pair, Photo } from './types'
import { suggestPairs } from './pairing'
import type { ScanResult } from './scanner'

export type ScanMode = 'standard' | 'deep'

// Allow bitmap decoding and result delivery outside the worker's scan budget.
export const SCAN_LIMITS = {
  standard: { workerBudgetMs: 18_000, timeoutMs: 22_000 },
  deep: { workerBudgetMs: 45_000, timeoutMs: 50_000 },
} as const

export function deepScanCandidates(photos: Photo[], pairs: Pair[]): Photo[] {
  const paired = new Set(pairs.flatMap(pair => [pair.frontId, pair.backId]))
  return photos.filter(photo => !paired.has(photo.id)
    && photo.scanState !== 'found' && photo.scanState !== 'ambiguous')
}

export function mergeScanEvidence(previous: ScanResult, retry: ScanResult): ScanResult {
  const certIds = [...new Set([...previous.certIds, ...retry.certIds])]
  const qrTexts = [...new Set([...previous.qrTexts, ...retry.qrTexts])]
  // A second pass must not erase evidence of a conflicting code, or turn an
  // earlier incomplete conflict check into a successful single-code result.
  const error = certIds.length > 1 ? undefined : retry.error
    || (certIds.length && !retry.certIds.length
      ? previous.error || '补扫未能确认已读到的证书号，请人工检查。' : undefined)
  return { certIds, qrTexts, ...(error ? { error } : {}) }
}

export function mergeDeepScanPairs(photos: Photo[], previous: Pair[], scannedIds: Set<string>): Pair[] {
  const used = new Set(previous.flatMap(pair => [pair.frontId, pair.backId]))
  const added = suggestPairs(photos).filter(pair => {
    // A retry cannot resurrect a removed group or take a photo from an
    // existing group, including one the user has deliberately deselected.
    if (!scannedIds.has(pair.backId) || used.has(pair.frontId) || used.has(pair.backId)) return false
    used.add(pair.frontId)
    used.add(pair.backId)
    return true
  })
  const positions = new Map(photos.map((photo, index) => [photo.id, index]))
  return [...previous, ...added].sort((a, b) => (positions.get(a.backId) ?? 0) - (positions.get(b.backId) ?? 0))
}
