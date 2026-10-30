import request from '@/utils/request'

export function getOrderAdmission(orderId) {
  return request({
    url: `/api/admin/order-admissions/${orderId}`,
    method: 'get'
  })
}

export function decideOrderAdmission(orderId, data) {
  return request({
    url: `/api/admin/order-admissions/${orderId}/decision`,
    method: 'post',
    data
  })
}

export function getPartnerSubmitAdmission(batchId) {
  return request({ url: `/api/admin/order-admissions/partner-submits/${batchId}`, method: 'get', suppressErrorMessage: true })
}

export function decidePartnerSubmitAdmission(batchId, data) {
  return request({ url: `/api/admin/order-admissions/partner-submits/${batchId}/decision`, method: 'post', data, suppressErrorMessage: true })
}

export function getOrderAdmissionConfig() {
  return request({
    url: '/api/admin/order-admissions/config',
    method: 'get'
  })
}

export function updateOrderAdmissionConfig(data) {
  return request({
    url: '/api/admin/order-admissions/config',
    method: 'put',
    data
  })
}
