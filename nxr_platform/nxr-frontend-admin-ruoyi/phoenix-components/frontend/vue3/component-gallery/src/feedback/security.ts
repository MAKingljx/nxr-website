export function canSendEditCredential(locationValue = globalThis.location, secureContext = globalThis.isSecureContext) {
  if (secureContext) return true
  const hostname = locationValue?.hostname?.toLocaleLowerCase() ?? ''
  return hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '::1'
}

export async function copySensitiveValue(value: string) {
  if (!value || !globalThis.navigator?.clipboard?.writeText) return false
  try {
    await globalThis.navigator.clipboard.writeText(value)
    return true
  } catch {
    return false
  }
}
