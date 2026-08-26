export function clampNumber(value: number, min: number, max: number) {
  const safe = Number.isFinite(value) ? value : min
  return Math.min(Math.max(safe, Math.min(min, max)), Math.max(min, max))
}

export function clampInteger(value: number, min: number, max: number) {
  return Math.trunc(clampNumber(value, min, max))
}

export function safeImageUrl(value?: string) {
  const candidate = value?.trim() ?? ''
  return /^(https?:\/\/|\/|\.\/|\.\.\/|blob:|data:image\/(?:png|gif|jpe?g|webp|avif|bmp|x-icon);base64,)/i.test(candidate)
    ? candidate
    : ''
}

export function formatCurrency(value: number, currency = 'CNY', locale = 'zh-CN') {
  const amount = clampNumber(value, 0, Number.MAX_SAFE_INTEGER)
  try {
    return new Intl.NumberFormat(locale, { style: 'currency', currency }).format(amount)
  } catch {
    return `${currency} ${amount.toFixed(2)}`
  }
}
