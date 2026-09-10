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
