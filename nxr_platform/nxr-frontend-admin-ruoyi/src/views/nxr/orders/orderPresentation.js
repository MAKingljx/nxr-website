export const orderDetailSections = ['admission', 'warehouse', 'cards', 'finance', 'shipping', 'support', 'timeline']

export function orderDetailSection(order, requested) {
  if (orderDetailSections.includes(requested)) return requested
  const status = order?.statusCode
  if (['awaiting_payment', 'payment_review', 'payment_exception'].includes(status)) return 'finance'
  if (['awaiting_inbound', 'inbound_shipped', 'intake_exception', 'received'].includes(status)) return 'warehouse'
  if (['grading', 'review', 'quality_check', 'quality_hold'].includes(status)) return 'cards'
  if (['completed', 'return_shipped'].includes(status)) return 'shipping'
  if (['delivered', 'cancelled'].includes(status)) return 'timeline'
  return 'admission'
}

export function orderManualStatuses(options, merchantBatch) {
  return merchantBatch
    ? options.filter(option => !['inbound_shipped', 'return_shipped', 'delivered'].includes(option.value))
    : options
}
