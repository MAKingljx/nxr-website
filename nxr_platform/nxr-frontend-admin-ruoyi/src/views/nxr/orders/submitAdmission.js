export function joinSubmitReviewOrders(orders = [], admissions = []) {
  const byId = new Map((admissions || []).map(item => [item.orderId, item]))
  return orders.map(order => ({ ...order, ...byId.get(order.orderId) }))
}

export function canReviewSubmit(orders, review) {
  if (!review?.eligible || !orders?.length || orders.length !== review.orders?.length) return false
  const byId = new Map(review.orders.map(item => [item.orderId, item]))
  return byId.size === orders.length && new Set(orders.map(order => order.orderId)).size === orders.length && orders.every(order => {
    const admission = byId.get(order.orderId)
    return order.statusCode === 'admission_review' && admission?.admissionStatus === 'pending_review' && Number.isInteger(admission.admissionRevision)
  })
}

export function buildSubmitDecision(review, decision, note) {
  return { decision, note: note.trim(), orders: review.orders.map(({ orderId, admissionRevision }) => ({ orderId, expectedRevision: admissionRevision })) }
}

export function submitDecisionReason(admission) {
  if (!['rejected', 'needs_information'].includes(admission?.admissionStatus)) return ''
  return typeof admission.decisionNote === 'string' && admission.decisionNote.trim() ? admission.decisionNote : ''
}
