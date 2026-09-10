import request from '@/utils/request'

export function fetchPaymentSettings() {
  return request({
    url: '/api/admin/payment-settings',
    method: 'get'
  })
}

export function updatePaymentSetting(provider, data) {
  return request({
    url: `/api/admin/payment-settings/${encodeURIComponent(provider)}`,
    method: 'put',
    data
  })
}
