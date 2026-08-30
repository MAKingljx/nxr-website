import { parseTime } from '@/utils/ruoyi'

const avatarPalette = ['#386f73', '#596f91', '#8a694a', '#6e5d86', '#4e785c']

export function formatCustomerDate(value) {
  return value ? parseTime(value, '{y}-{m}-{d} {h}:{i}') : '-'
}

export function avatarText(name) {
  return (name || 'U').trim().slice(0, 1).toUpperCase()
}

export function avatarColor(id) {
  return avatarPalette[Math.abs(Number(id) || 0) % avatarPalette.length]
}

export function formatGrade(card) {
  if (card.productType === 'merch_product' || card.productType === 'label_product') return tx('Merch Product')
  if (card.productType === 'vintage_product') return card.vintageClassification || tx('Vintage Card')
  if (card.finalGradeValue === null || card.finalGradeValue === undefined) return '-'
  return `${Number(card.finalGradeValue).toFixed(1)} ${card.finalGradeLabel || ''}`.trim()
}

export function ownershipLabel(status) {
  return ({ active: tx('Owned'), released: tx('Released'), transferred: tx('Transferred') })[status] || status || '-'
}

export function eventLabel(type) {
  return ({ bound: tx('First Bound'), transferred: tx('Card Transferred'), released: tx('Ownership Released') })[type] || type || tx('Ownership Event')
}

export function transferLabel(event) {
  const from = event.fromDisplayName || tx('Unassigned')
  const to = event.toDisplayName || tx('Unassigned')
  return `${from} → ${to}`
}

export function orderStatusLabel(status) {
  return ({
    draft: tx('Draft'), awaiting_payment: tx('Awaiting Payment'), payment_review: tx('Payment Review'),
    inbound_shipped: tx('Inbound Shipping'), received: tx('Received'), grading: tx('Grading'),
    completed: tx('Completed'), return_shipped: tx('Return Shipping'), delivered: tx('Delivered'), cancelled: tx('Cancelled')
  })[status] || status || '-'
}

export function formatAmount(order) {
  const amount = Number(order.totalAmount)
  return `${order.currencyCode || ''} ${Number.isFinite(amount) ? amount.toFixed(2) : '-'}`.trim()
}
