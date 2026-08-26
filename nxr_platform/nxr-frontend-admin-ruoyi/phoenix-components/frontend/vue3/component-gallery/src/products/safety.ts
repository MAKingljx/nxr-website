/**
 * Returns a normalized URL only when it is an absolute HTTP(S) URL without
 * embedded credentials. Catalog data remains inert and is never fetched here.
 */
export function toSafeProductUrl(value: string | null | undefined): string | null {
  if (!value) return null

  try {
    const parsed = new URL(value)
    if (!['http:', 'https:'].includes(parsed.protocol)) return null
    if (parsed.username || parsed.password) return null
    return parsed.href
  } catch {
    return null
  }
}
