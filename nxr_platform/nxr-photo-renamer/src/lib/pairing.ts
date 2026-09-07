import type { Pair, Photo } from './types'

const CERTIFICATE_ID = /^[A-Za-z0-9]{1,64}$/
const IMAGE_EXTENSION = /\.(webp|jpe?g|png)$/i

export function naturalCompare(a: string, b: string): number {
  const natural = a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' })
  if (natural !== 0 || a === b) return natural
  // Numeric collation considers IMG_2/IMG_02 and case variants equal. The
  // code-unit fallback keeps directory enumeration order from changing pairs.
  return a < b ? -1 : 1
}

export function isSupportedImage(name: string): boolean {
  return IMAGE_EXTENSION.test(name.trim())
}

export function parseCertificateLink(text: string): string | null {
  const raw = text.trim()
  // Printed NXR labels also contain the domain without an http(s) scheme.
  // Normalize only our exact host, then run the same path/host validation.
  const value = /^(?:www\.)?nxrgrading\.com(?:\/|$)/i.test(raw) ? `https://${raw}` : raw
  if (!value || hasUnsafeRawPath(value)) return null

  let pathname: string
  if (value.startsWith('/')) {
    if (value.startsWith('//')) return null
    try {
      const parsed = new URL(value, 'https://nxrgrading.com')
      if (parsed.origin !== 'https://nxrgrading.com') return null
      pathname = parsed.pathname
    } catch {
      return null
    }
  } else {
    let parsed: URL
    try {
      parsed = new URL(value)
    } catch {
      return null
    }
    if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') return null
    if (parsed.username || parsed.password) return null
    const hostname = parsed.hostname.toLowerCase()
    if (hostname !== 'nxrgrading.com' && hostname !== 'www.nxrgrading.com') return null
    pathname = parsed.pathname
  }

  // Match the literal path. Encoded separators and dot segments are deliberately
  // rejected instead of being decoded into a certificate identifier.
  const match = /^\/card\/([A-Za-z0-9]{1,64})\/?$/.exec(pathname)
  return match?.[1] ?? null
}

export function suggestPairs(photos: Photo[]): Pair[] {
  const pairs: Pair[] = []
  const used = new Set<string>()

  for (let backIndex = 1; backIndex < photos.length; backIndex += 1) {
    const back = photos[backIndex]
    const front = photos[backIndex - 1]
    const certIds = [...new Set(back.certIds.filter(isStrictCertificateId))]

    if (
      back.scanState !== 'found'
      || certIds.length !== 1
      || used.has(front.id)
      || used.has(back.id)
    ) {
      continue
    }

    const certId = certIds[0]
    pairs.push({
      id: `auto:${front.id}:${back.id}:${certId}`,
      frontId: front.id,
      backId: back.id,
      certId,
      selected: true,
    })
    used.add(front.id)
    used.add(back.id)
  }

  return pairs
}

export function validatePairs(
  photos: Photo[],
  pairs: Pair[],
  allFilenames: string[],
): Map<string, string[]> {
  const errors = new Map<string, string[]>()
  const photosById = new Map(photos.map((photo) => [photo.id, photo]))
  const pairById = new Map(pairs.map((pair) => [pair.id, pair]))
  const sourceUsers = new Map<string, Set<string>>()
  const certUsers = new Map<string, Set<string>>()
  const targetUsers = new Map<string, Set<string>>()
  const existingNames = countCaseInsensitive(allFilenames)

  const add = (pairId: string, message: string) => {
    const current = errors.get(pairId) ?? []
    if (!current.includes(message)) current.push(message)
    errors.set(pairId, current)
  }

  for (const pair of pairs) {
    const front = photosById.get(pair.frontId)
    const back = photosById.get(pair.backId)

    if (!front) add(pair.id, '缺少正面图片。')
    if (!back) add(pair.id, '缺少背面图片。')
    if (pair.frontId === pair.backId) add(pair.id, '正面和背面不能使用同一张图片。')

    for (const sourceId of new Set([pair.frontId, pair.backId])) {
      const users = sourceUsers.get(sourceId) ?? new Set<string>()
      users.add(pair.id)
      sourceUsers.set(sourceId, users)
    }

    if (!isStrictCertificateId(pair.certId)) {
      add(pair.id, '证书号只能包含 1–64 个 ASCII 字母或数字。')
    } else {
      const key = pair.certId.toLowerCase()
      const users = certUsers.get(key) ?? new Set<string>()
      users.add(pair.id)
      certUsers.set(key, users)
    }

    if (back) {
      const scannedIds = [...new Set(back.certIds.filter(isStrictCertificateId))]
      const knownQrMismatch = pair.manual
        ? scannedIds.length > 0 && !scannedIds.includes(pair.certId)
        : false
      const invalidAutomaticBack = !pair.manual
        && (back.scanState !== 'found' || scannedIds.length !== 1 || scannedIds[0] !== pair.certId)
      if (knownQrMismatch || invalidAutomaticBack) {
        add(pair.id, '证书号与背面二维码不一致，请重新识别。')
      }
    }

    if (front && isStrictCertificateId(pair.certId)) {
      registerTarget(pair, front, 'A', targetUsers, existingNames, add)
    }
    if (back && isStrictCertificateId(pair.certId)) {
      registerTarget(pair, back, 'B', targetUsers, existingNames, add)
    }
  }

  for (const pairIds of sourceUsers.values()) {
    if (pairIds.size > 1) {
      for (const pairId of pairIds) add(pairId, '同一张源图片被多个配对重复使用。')
    }
  }

  for (const pairIds of certUsers.values()) {
    if (pairIds.size > 1) {
      for (const pairId of pairIds) add(pairId, '同一证书号被多个配对重复使用。')
    }
  }

  for (const pairIds of targetUsers.values()) {
    if (pairIds.size > 1) {
      for (const pairId of pairIds) add(pairId, '目标文件名与其他配对冲突（忽略大小写）。')
    }
  }

  // Ignore stale error keys if callers reuse a map-shaped result in the UI.
  for (const pairId of errors.keys()) {
    if (!pairById.has(pairId)) errors.delete(pairId)
  }
  return errors
}

function registerTarget(
  pair: Pair,
  photo: Photo,
  side: 'A' | 'B',
  targetUsers: Map<string, Set<string>>,
  existingNames: Map<string, number>,
  add: (pairId: string, message: string) => void,
) {
  const extension = imageExtension(photo.name)
  if (!extension) {
    add(pair.id, `${side === 'A' ? '正面' : '背面'}图片的扩展名不受支持。`)
    return
  }
  const target = `${pair.certId}_${side}.webp`
  const targetKey = target.toLowerCase()
  const sourceKey = basename(photo.name).toLowerCase()
  const occupied = existingNames.get(targetKey) ?? 0

  // The source itself may already have the exact target name. That is a valid
  // no-op pair; any additional case-insensitive occupant remains a collision.
  const selfOccupancy = sourceKey === targetKey ? 1 : 0
  if (occupied > selfOccupancy) {
    add(pair.id, `目标文件名已存在：${target}`)
  }

  const users = targetUsers.get(targetKey) ?? new Set<string>()
  users.add(pair.id)
  targetUsers.set(targetKey, users)
}

function imageExtension(name: string): string | null {
  const match = IMAGE_EXTENSION.exec(basename(name))
  return match?.[1] ?? null
}

function basename(name: string): string {
  return name.replaceAll('\\', '/').split('/').pop() ?? name
}

function countCaseInsensitive(names: string[]): Map<string, number> {
  const counts = new Map<string, number>()
  for (const name of names) {
    const key = basename(name).toLowerCase()
    counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  return counts
}

function isStrictCertificateId(value: string): boolean {
  return CERTIFICATE_ID.test(value)
}

function hasUnsafeRawPath(value: string): boolean {
  let rawPath = value
  if (!value.startsWith('/')) {
    const match = /^[A-Za-z][A-Za-z0-9+.-]*:\/\/[^/]*(\/[^?#]*)?(?:[?#]|$)/.exec(value)
    if (!match) return false
    rawPath = match[1] ?? '/'
  } else {
    rawPath = value.split(/[?#]/, 1)[0]
  }
  if (rawPath.includes('\\') || /%(?:2e|2f|5c)/i.test(rawPath)) return true
  return rawPath.split('/').some((segment) => segment === '.' || segment === '..')
}
