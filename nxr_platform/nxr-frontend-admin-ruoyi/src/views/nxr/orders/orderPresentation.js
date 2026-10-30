export const orderDetailSections = ['admission', 'warehouse', 'cards', 'finance', 'shipping', 'support', 'timeline']

export const paymentOrderStatuses = new Set(['awaiting_payment', 'payment_review', 'payment_expired', 'payment_exception'])
export const isPartnerOrder = order => order?.orderChannel ? order.orderChannel === 'partner' : Boolean(order?.merchantBatch)

export function orderChannelFromRoute(channel, adminUnified) {
  return channel === 'individual' || channel === 'partner' ? channel : adminUnified ? 'partner' : undefined
}

export function showIntakeLookup(channel, detailOpen, detail) {
  return channel !== 'partner' && !(detailOpen && detail && isPartnerOrder(detail))
}

export function orderDetailSection(order, requested) {
  if (orderDetailSections.includes(requested) && !(requested === 'finance' && isPartnerOrder(order))) return requested
  const status = order?.statusCode
  if (['awaiting_payment', 'payment_review', 'payment_exception'].includes(status)) return isPartnerOrder(order) ? 'admission' : 'finance'
  if (['awaiting_inbound', 'inbound_shipped', 'intake_exception', 'received'].includes(status)) return 'warehouse'
  if (['grading', 'review', 'quality_check', 'quality_hold'].includes(status)) return 'cards'
  if (['completed', 'return_shipped'].includes(status)) return 'shipping'
  if (['delivered', 'cancelled'].includes(status)) return 'timeline'
  return 'admission'
}

export function orderManualStatuses(options, merchantBatch) {
  return merchantBatch
    ? options.filter(option => !paymentOrderStatuses.has(option.value) && !['inbound_shipped', 'return_shipped', 'delivered'].includes(option.value))
    : options
}
