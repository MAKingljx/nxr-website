export type PhoenixContentAppearance = 'modern' | 'minimal' | 'soft'

export function normalizeAppearance(value: PhoenixContentAppearance): PhoenixContentAppearance {
  return value === 'minimal' || value === 'soft' ? value : 'modern'
}

function normalizedUrl(value?: string) {
  return value?.trim() ?? ''
}

export function safeImageUrl(value?: string) {
  const url = normalizedUrl(value)
  if (!url) return ''
  if (/^(?:https?:\/\/|\/(?!\/)|\.\.?\/|blob:)/i.test(url)) return url
  if (/^data:image\/(?:avif|gif|jpeg|jpg|png|webp);base64,[a-z0-9+/=\s]+$/i.test(url)) return url
  return ''
}

export function safeResourceUrl(value?: string) {
  const url = normalizedUrl(value)
  return /^(?:https?:\/\/|\/(?!\/)|\.\.?\/|blob:)/i.test(url) ? url : ''
}

export function finiteInteger(value: number | undefined, fallback: number, minimum = 0) {
  return Math.max(minimum, Math.trunc(Number.isFinite(value) ? value ?? fallback : fallback))
}

export function formatFileSize(bytes?: number) {
  if (bytes === undefined || !Number.isFinite(bytes) || bytes < 0) return '大小未知'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(bytes < 10 * 1024 ? 1 : 0)} KB`
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(bytes < 10 * 1024 ** 2 ? 1 : 0)} MB`
  return `${(bytes / 1024 ** 3).toFixed(1)} GB`
}
