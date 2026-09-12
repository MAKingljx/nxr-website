import { digest, scanCache } from './scan-cache'
import { createManualPair } from './manual-pairing'
import { suggestPairs } from './pairing'
import type { Pair, Photo } from './types'

interface SessionState { interrupted: boolean; pairs: Pair[]; omittedBackIds: string[] }
export async function sessionKey(photos: Photo[]): Promise<string | undefined> {
  if (!photos.length || photos.some(photo => !photo.contentHash)) return undefined
  return digest(JSON.stringify(photos.map(photo => [photo.name, photo.contentHash])))
}
export async function saveScanSession(key: string | undefined, photos: Photo[], pairs: Pair[], interrupted: boolean, cache = scanCache): Promise<void> {
  if (!key) return
  const kept = new Set(pairs.map(pair => pair.backId))
  const value: SessionState = { interrupted, pairs: structuredClone(pairs.map(pair => ({ ...pair }))),
    omittedBackIds: suggestPairs(photos).filter(pair => !kept.has(pair.backId)).map(pair => pair.backId) }
  await cache.writeSession(key, value)
}
export async function restoreScanSession(key: string | undefined, photos: Photo[], allNames: string[], cache = scanCache): Promise<{ pairs: Pair[]; interrupted: boolean }> {
  const automatic = suggestPairs(photos)
  if (!key) return { pairs: automatic, interrupted: false }
  const value = await cache.readSession(key) as Partial<SessionState> | undefined
  if (!value || typeof value.interrupted !== 'boolean' || !Array.isArray(value.pairs) || value.pairs.length > photos.length
    || !Array.isArray(value.omittedBackIds) || !value.omittedBackIds.every(id => typeof id === 'string')) return { pairs: automatic, interrupted: false }
  const restored: Pair[] = []
  for (const saved of value.pairs) {
    if (!saved || typeof saved.certId !== 'string' || typeof saved.backId !== 'string' || typeof saved.selected !== 'boolean') continue
    if (saved.manual === true) {
      try {
        const pair = createManualPair(photos, restored, saved.backId, saved.certId, allNames)
        if (pair.frontId === saved.frontId) restored.push({ ...pair, selected: saved.selected })
      } catch { /* Invalid or stale manual decisions never bypass validation. */ }
    }
  }
  const used = new Set(restored.flatMap(pair => [pair.frontId, pair.backId]))
  for (const pair of automatic) {
    if (used.has(pair.frontId) || used.has(pair.backId) || value.omittedBackIds.includes(pair.backId)) continue
    const saved = value.pairs.find(item => item.backId === pair.backId && item.certId === pair.certId && item.frontId === pair.frontId && !item.manual)
    restored.push({ ...pair, selected: typeof saved?.selected === 'boolean' ? saved.selected : true })
    used.add(pair.frontId); used.add(pair.backId)
  }
  const positions = new Map(photos.map((photo, index) => [photo.id, index]))
  restored.sort((a, b) => positions.get(a.backId)! - positions.get(b.backId)!)
  return { pairs: restored, interrupted: value.interrupted }
}
