const SAFE_IMAGE_DATA = /^data:image\/(?:png|jpe?g|gif|webp|avif);base64,[a-z0-9+/=]+$/i
const SAFE_WEB_URL = /^https?:\/\/[^\s]+$/i
const SAFE_LOCAL_URL = /^(?:\/(?!\/)|\.\/)[^\s]*$/

export function safeImageUrl(value?: string): string {
  const url = value?.trim() ?? ''
  if (!url) return ''
  return SAFE_WEB_URL.test(url) || SAFE_LOCAL_URL.test(url) || SAFE_IMAGE_DATA.test(url) ? url : ''
}

export function safeReplayUrl(value?: string): string {
  const url = value?.trim() ?? ''
  if (!url) return ''
  return SAFE_WEB_URL.test(url) || SAFE_LOCAL_URL.test(url) ? url : ''
}

export function safeCount(value: number, maximum = 999_999_999): number {
  if (!Number.isFinite(value)) return 0
  return Math.min(maximum, Math.max(0, Math.trunc(value)))
}

export function safeAmount(value: number): number {
  if (!Number.isFinite(value)) return 0
  return Math.min(999_999_999_999, Math.max(0, value))
}
