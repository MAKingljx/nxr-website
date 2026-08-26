const STATUS_TONES = new Set(['primary', 'success', 'warning', 'danger', 'neutral'])

const COMMON_STATUS_MAP = Object.freeze({
  active: { label: '启用', tone: 'success' },
  inactive: { label: '停用', tone: 'neutral' }
})

const DOMAIN_STATUS_MAP = Object.freeze({
  entries: Object.freeze({
    pending: { label: '待处理', tone: 'warning' },
    review: { label: '审核中', tone: 'primary' },
    approved: { label: '已通过', tone: 'success' },
    published: { label: '已发布', tone: 'success' }
  }),
  waitlist: Object.freeze({
    pending: { label: '待确认', tone: 'warning' },
    confirmed: { label: '已确认', tone: 'success' }
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
