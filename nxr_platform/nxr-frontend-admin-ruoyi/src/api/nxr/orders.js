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
