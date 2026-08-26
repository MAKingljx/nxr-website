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
  if (card.productType === 'merch_product' || card.productType === 'label_product') return 'Merch Product'
  if (card.productType === 'vintage_product') return card.vintageClassification || 'Vintage Card'
  if (card.finalGradeValue === null || card.finalGradeValue === undefined) return '-'
  return `${Number(card.finalGradeValue).toFixed(1)} ${card.finalGradeLabel || ''}`.trim()
}

export function ownershipLabel(status) {
  return ({ active: '持有中', released: '已释放', transferred: '已转让' })[status] || status || '-'
}

export function eventLabel(type) {
  return ({ bound: '首次绑定', transferred: '卡片转让', released: '解除绑定' })[type] || type || '流转记录'
}

export function transferLabel(event) {
  const from = event.fromDisplayName || '未绑定'
  const to = event.toDisplayName || '未绑定'
  return `${from} → ${to}`
}

export function orderStatusLabel(status) {
  return ({
    draft: '待提交', awaiting_payment: '待付款', payment_review: '付款审核',
    inbound_shipped: '寄送中', received: '已收件', grading: '评级中',
    completed: '已完成', return_shipped: '回寄中', delivered: '已送达', cancelled: '已取消'
  })[status] || status || '-'
}

export function formatAmount(order) {
  const amount = Number(order.totalAmount)
  return `${order.currencyCode || ''} ${Number.isFinite(amount) ? amount.toFixed(2) : '-'}`.trim()
}
