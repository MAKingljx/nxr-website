import { parseCertificateLink } from './pairing'
import type { TextReference } from './types'

const CERTIFICATE_ID = /^[A-Za-z0-9]{1,64}$/
const OCR_CANDIDATE = /(?<![A-Za-z0-9])[A-Za-z0-9]{6,64}(?![A-Za-z0-9])/g

export function normalizeReferenceText(value: string): string {
  const ascii = [...value].map((character) => {
    const code = character.charCodeAt(0)
    if (code >= 0xff01 && code <= 0xff5e) return String.fromCharCode(code - 0xfee0)
    if (code === 0x3000) return ' '
    return character
  }).join('').trim()

  // Remove layout whitespace only when it sits between digits. Letter/digit
  // substitutions and other OCR guesses are deliberately left untouched.
  return ascii.replace(/([0-9])[ \t\u00a0]+(?=[0-9])/g, '$1')
}

export function normalizeCertificateInput(value: string): string | null {
  const normalized = normalizeReferenceText(value)
  const linked = parseCertificateLink(normalized)
  if (linked) return linked
  return CERTIFICATE_ID.test(normalized) ? normalized : null
}

export function extractTextReferenceCandidates(rawText: string): string[] {
  const normalized = normalizeReferenceText(rawText)
  return [...new Set((normalized.match(OCR_CANDIDATE) ?? []).filter(candidate => /[0-9]/.test(candidate)))]
}

export function classifyTextReference(rawText: string, qrCertId?: string): TextReference {
  const candidates = extractTextReferenceCandidates(rawText)
  if (candidates.length === 0) return { state: 'unreadable', candidates, rawText }
  if (!qrCertId) return { state: 'reference', candidates, rawText }
  return {
    state: candidates.includes(qrCertId) ? 'matched' : 'mismatch',
    candidates,
    rawText,
  }
}
