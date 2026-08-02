import request from '@/utils/request'

export function listCustomers(query) {
  return request({ url: '/api/admin/customers', method: 'get', params: query })
}

export function getCustomer(customerId) {
  return request({ url: `/api/admin/customers/${customerId}`, method: 'get' })
}

export function updateCustomerStatus(customerId, active) {
  return request({
    url: `/api/admin/customers/${customerId}/status`,
    method: 'put',
    data: { active }
  })
}

export function revokeCustomerSessions(customerId) {
  return request({
    url: `/api/admin/customers/${customerId}/sessions/revoke`,
    method: 'post'
  })
}
