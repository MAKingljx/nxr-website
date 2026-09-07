import { naturalCompare, validatePairs } from './pairing'
import { normalizeCertificateInput, normalizeReferenceText } from './text-reference-policy'
import type { Pair, Photo } from './types'

export function createManualPair(
  photos: Photo[],
  pairs: Pair[],
  backId: string,
  certId: string,
  allFilenames: string[],
): Pair {
  const ordered = [...photos].sort((left, right) => naturalCompare(left.name, right.name))
  const backIndex = ordered.findIndex(photo => photo.id === backId)
  if (backIndex < 0) throw new Error('找不到所选的背面图片。')
  if (backIndex === 0) throw new Error('所选背面图片在自然顺序中没有前一张图片可作为正面。')

  const front = ordered[backIndex - 1]
  const back = ordered[backIndex]
  if (pairs.some(pair => pair.frontId === front.id
    || pair.backId === front.id
    || pair.frontId === back.id
    || pair.backId === back.id)) {
    throw new Error('所选正面或背面图片已被已有配对使用（包括未勾选的配对）。')
  }

  const normalizedCertId = normalizeCertificateInput(certId) ?? normalizeReferenceText(certId)
  const candidate: Pair = {
    id: `manual:${front.id}:${back.id}:${normalizedCertId}`,
    frontId: front.id,
    backId: back.id,
    certId: normalizedCertId,
    selected: true,
    manual: true,
  }

  const errors = validatePairs(photos, [...pairs, candidate], allFilenames).get(candidate.id)
  if (errors?.length) throw new Error(errors.join(' '))
  return candidate
}
