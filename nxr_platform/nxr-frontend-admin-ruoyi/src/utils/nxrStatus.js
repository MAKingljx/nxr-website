const STATUS_TONES = new Set(['primary', 'success', 'warning', 'danger', 'neutral'])

const COMMON_STATUS_MAP = Object.freeze({
  active: { label: tx('Active'), tone: 'success' },
  inactive: { label: tx('Inactive'), tone: 'neutral' }
})

const DOMAIN_STATUS_MAP = Object.freeze({
  entries: Object.freeze({
    pending: { label: tx('Pending'), tone: 'warning' },
    review: { label: tx('In Review'), tone: 'primary' },
    approved: { label: tx('Approved'), tone: 'success' },
    published: { label: tx('Published'), tone: 'success' }
  }),
  waitlist: Object.freeze({
    pending: { label: tx('Pending'), tone: 'warning' },
    confirmed: { label: tx('Confirmed'), tone: 'success' }
  }),
  general: COMMON_STATUS_MAP
})

function normalize(value) {
  return String(value ?? '').trim().toLowerCase()
}

export function resolveNxrStatus({ code, label, tone, domain = 'general' } = {}) {
  const normalizedCode = normalize(code)
  const normalizedDomain = normalize(domain) || 'general'
  const mapped =
    DOMAIN_STATUS_MAP[normalizedDomain]?.[normalizedCode] ||
    COMMON_STATUS_MAP[normalizedCode]
  const explicitLabel = String(label ?? '').trim()
  const explicitTone = normalize(tone)

  return {
    label: explicitLabel || mapped?.label || String(code ?? '').trim() || '-',
    tone: STATUS_TONES.has(explicitTone) ? explicitTone : mapped?.tone || 'neutral'
  }
}
