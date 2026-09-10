import type { GradingOrder } from './customer'

const labels: Record<string, string> = {
  admission_review: 'Application under review', terms_confirmation: 'Confirm order and terms', payment_expired: 'Payment deadline passed',
  pending_review: 'Application under review', needs_information: 'More information needed', approved: 'Application accepted', expired: 'Payment deadline passed',
  awaiting_payment: 'Awaiting payment', payment_review: 'Payment under review', payment_exception: 'Payment requires review', awaiting_inbound: 'Ready to send to NXR',
  inbound_shipped: 'On the way to NXR', received: 'Received by NXR', intake_exception: 'Receiving needs attention',
  grading: 'Grading in progress', review: 'Review and encapsulation', quality_check: 'Final quality check',
  quality_hold: 'Quality review in progress', completed: 'Ready for return shipment', return_shipped: 'On the way back to you',
  delivered: 'Delivered', cancelled: 'Cancelled', pending: 'Pending', confirmed: 'Confirmed', rejected: 'Rejected',
  open: 'Preparing customer orders', processing: 'In progress', in_transit: 'In transit', out_for_delivery: 'Out for delivery', exception: 'Delivery exception', label_created: 'Label created',
}

export function orderStatusLabel(status?: string | null) {
  return status ? labels[status] || status.replaceAll('_', ' ') : '—'
}

export function orderDisplayStatus(statusCode: string, admissionStatus?: string | null) {
  return statusCode === 'admission_review' && admissionStatus ? admissionStatus : statusCode
}

export function formatMoney(amount: number | string, currency: string) {
  return new Intl.NumberFormat('en', { style: 'currency', currency, currencyDisplay: 'code' }).format(Number(amount))
}

const milestones = [
  { label: 'Order placed', statuses: ['admission_review', 'terms_confirmation', 'payment_expired', 'awaiting_payment', 'payment_review'] },
  { label: 'Payment confirmed', statuses: ['awaiting_inbound'] },
  { label: 'Inbound shipping', statuses: ['inbound_shipped'] },
  { label: 'Received by NXR', statuses: ['received', 'intake_exception'] },
  { label: 'Grading', statuses: ['grading'] },
  { label: 'Review & encapsulation', statuses: ['review', 'quality_check', 'quality_hold', 'completed'] },
  { label: 'Return shipping', statuses: ['return_shipped'] },
  { label: 'Delivered', statuses: ['delivered'] },
]

export function orderProgress(order: Pick<GradingOrder, 'statusCode' | 'createdAt' | 'timeline' | 'shipments' | 'payments'>) {
  const current = milestones.findIndex((step) => step.statuses.includes(order.statusCode))
  return milestones.map((step, index) => {
    // Timestamps require real evidence. Skipped stages never receive invented dates.
    const times = order.timeline.filter((event) => step.statuses.includes(event.statusCode || '') && !event.eventCode.startsWith('shipment_')).map((event) => event.createdAt)
    if (index === 0) times.push(order.createdAt)
    if (index === 1) times.push(...order.payments.filter((payment) => payment.paymentTypeCode === 'grading_fee' && payment.statusCode === 'confirmed' && payment.confirmedAt).map((payment) => payment.confirmedAt!))
    if (index === 2 || index === 6) times.push(...order.shipments.filter((shipment) => shipment.directionCode === (index === 2 ? 'inbound' : 'outbound')).map((shipment) => shipment.shippedAt))
    if (index === 7) times.push(...order.shipments.filter((shipment) => shipment.directionCode === 'outbound' && shipment.deliveredAt).map((shipment) => shipment.deliveredAt!))
    times.sort()
    const evidenceOnly = ['cancelled', 'payment_exception'].includes(order.statusCode)
    const delivered = order.statusCode === 'delivered'
    return { label: step.label, reachedAt: times[0] || null, active: !delivered && current === index, completed: evidenceOnly ? times.length > 0 : delivered || current > index }
  })
}
