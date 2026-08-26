import type { PhoenixAnalyticsValueKind } from './types'

const MAX_ABSOLUTE_VALUE = 1_000_000_000_000

export const chartPalette = ['#5b5ce2', '#22a06b', '#e56910', '#d83a52', '#1d7afc', '#8b5cf6', '#0f9fa8', '#c2418d']

export function safeSigned(value: number, fallback = 0) {
  if (!Number.isFinite(value)) return fallback
  return Math.min(MAX_ABSOLUTE_VALUE, Math.max(-MAX_ABSOLUTE_VALUE, value))
}

export function safePositive(value: number) {
  return Math.max(0, safeSigned(value))
}

export function safePercent(value: number) {
  return Math.min(100, safePositive(value))
}

export function safeDimension(value: number, fallback: number, minimum: number, maximum: number) {
  if (!Number.isFinite(value)) return fallback
  return Math.min(maximum, Math.max(minimum, value))
}

export function safeColor(value: string | undefined, index = 0) {
  const fallback = chartPalette[index % chartPalette.length]
  if (!value) return fallback
  const normalized = value.trim()
  if (/^#[\da-f]{3,8}$/i.test(normalized)) return normalized
  if (/^(?:rgb|hsl)a?\([\d\s.,%+-]+\)$/i.test(normalized)) return normalized
  if (/^var\(--[a-z\d-_]+\)$/i.test(normalized)) return normalized
  return fallback
}

export function formatAnalyticsValue(
  value: number,
  kind: PhoenixAnalyticsValueKind = 'number',
  locale = 'zh-CN',
  currency = 'CNY',
  maximumFractionDigits = 2,
) {
  const safeValue = kind === 'percent' ? safePercent(value) : safeSigned(value)
  const safeDigits = Math.round(safeDimension(maximumFractionDigits, 2, 0, 6))
  try {
    if (kind === 'currency') {
      const safeCurrency = /^[A-Z]{3}$/.test(currency) ? currency : 'CNY'
      return new Intl.NumberFormat(locale, {
        style: 'currency',
        currency: safeCurrency,
        maximumFractionDigits: safeDigits,
      }).format(safeValue)
    }
    const formatted = new Intl.NumberFormat(locale, { maximumFractionDigits: safeDigits }).format(safeValue)
    return kind === 'percent' ? `${formatted}%` : formatted
  } catch {
    const formatted = safeValue.toFixed(safeDigits)
    return kind === 'currency' ? `CNY ${formatted}` : kind === 'percent' ? `${formatted}%` : formatted
  }
}

export function chartRange(values: number[]) {
  if (!values.length) return { minimum: 0, maximum: 1, span: 1 }
  const minimumValue = Math.min(...values)
  const maximumValue = Math.max(...values)
  const minimum = minimumValue === maximumValue ? Math.min(0, minimumValue) : minimumValue
  const maximum = minimumValue === maximumValue ? Math.max(1, maximumValue) : maximumValue
  return { minimum, maximum, span: Math.max(1, maximum - minimum) }
}

export function svgPolylinePoints(values: number[], width: number, height: number, padding: number) {
  if (!values.length) return []
  const { maximum, span } = chartRange(values)
  const innerWidth = Math.max(1, width - padding * 2)
  const innerHeight = Math.max(1, height - padding * 2)
  return values.map((value, index) => ({
    x: padding + (values.length === 1 ? innerWidth / 2 : (index / (values.length - 1)) * innerWidth),
    y: padding + ((maximum - value) / span) * innerHeight,
  }))
}

export function uniqueByKey<T extends { key: string }>(items: T[]) {
  const seen = new Set<string>()
  return items.filter((item) => item.key.length > 0 && !seen.has(item.key) && Boolean(seen.add(item.key)))
}
