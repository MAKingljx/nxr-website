import request from '@/utils/request'

export function listGradingOrders(query) {
  return request({ url: '/api/admin/orders', method: 'get', params: query })
}

export function getGradingOrder(orderId) {
  return request({ url: '/api/admin/orders/' + orderId, method: 'get' })
}

export function confirmGradingPayment(orderId, paymentId, data) {
  return request({ url: `/api/admin/orders/${orderId}/payments/${paymentId}/confirm`, method: 'post', data })
}

export function rejectGradingPayment(orderId, paymentId, data) {
  return request({ url: `/api/admin/orders/${orderId}/payments/${paymentId}/reject`, method: 'post', data })
}

export function updateGradingOrderStatus(orderId, data) {
  return request({ url: `/api/admin/orders/${orderId}/status`, method: 'post', data })
}

export function createGradingShipment(orderId, data) {
  return request({ url: `/api/admin/orders/${orderId}/shipments`, method: 'post', data })
}

export function markGradingShipmentDelivered(orderId, shipmentId) {
  return request({ url: `/api/admin/orders/${orderId}/shipments/${shipmentId}/delivered`, method: 'post' })
}

export function linkGradingOrderItem(orderId, itemId, submissionId) {
  return request({ url: `/api/admin/orders/${orderId}/items/${itemId}/link-submission`, method: 'post', params: { submissionId } })
}

export function getOrderOperations(orderId) {
  return request({ url: `/api/admin/orders/${orderId}/operations`, method: 'get' })
}

export function lookupOrderIntake(intakeCode) {
  return request({ url: '/api/admin/orders/intake/lookup', method: 'get', params: { intakeCode } })
}

export function receiveGradingOrder(orderId, data) {
  return request({ url: `/api/admin/orders/${orderId}/intake/receive`, method: 'post', data })
}

export function createOrderException(orderId, data) {
  return request({ url: `/api/admin/orders/${orderId}/exceptions`, method: 'post', data })
}

export function resolveOrderException(orderId, exceptionId, data) {
  return request({ url: `/api/admin/orders/${orderId}/exceptions/${exceptionId}/resolve`, method: 'post', data })
}

export function createOrderTask(orderId, data) {
  return request({ url: `/api/admin/orders/${orderId}/tasks`, method: 'post', data })
}

export function updateOrderTask(orderId, taskId, data) {
  return request({ url: `/api/admin/orders/${orderId}/tasks/${taskId}`, method: 'post', data })
}

export function runOrderQualityCheck(orderId, data) {
  return request({ url: `/api/admin/orders/${orderId}/quality-check`, method: 'post', data })
}

export function addShipmentTrackingEvent(orderId, shipmentId, data) {
  return request({ url: `/api/admin/orders/${orderId}/shipments/${shipmentId}/tracking`, method: 'post', data })
}

export function updateOrderTicket(orderId, ticketId, data) {
  return request({ url: `/api/admin/orders/${orderId}/tickets/${ticketId}`, method: 'post', data })
}

export function reviewOrderShippingChange(orderId, requestId, data) {
  return request({ url: `/api/admin/orders/${orderId}/shipping-changes/${requestId}/review`, method: 'post', data })
}

export function settleOrderShippingChange(orderId, requestId, data) {
  return request({ url: `/api/admin/orders/${orderId}/shipping-changes/${requestId}/settle`, method: 'post', data })
}

export function listReturnShippingOptions(country) {
  return request({ url: '/api/admin/orders/shipping-options', method: 'get', params: { country } })
}

export function getGradingServicePrice() {
  return request({ url: '/api/admin/orders/service-price', method: 'get' })
}

export function saveGradingServicePrice(data) {
  return request({ url: '/api/admin/orders/service-price', method: 'post', data })
}

export function saveReturnShippingOption(optionId, data) {
  return request({ url: optionId ? `/api/admin/orders/shipping-options/${optionId}` : '/api/admin/orders/shipping-options', method: 'post', data })
}
