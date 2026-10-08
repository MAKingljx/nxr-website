import request from '@/utils/request'

const base = '/api/admin/card-operators'

export function listCardOperators(params) {
  return request({ url: base, method: 'get', params })
}

export function createCardOperator(data) {
  // Credential bodies must not enter the generic sessionStorage repeat-submit cache.
  return request({ url: base, method: 'post', data, headers: { repeatSubmit: false } })
}

export function changeCardOperatorStatus(userId, status) {
  return request({ url: `${base}/${userId}/status`, method: 'put', data: { status } })
}

export function resetCardOperatorPassword(userId, password) {
  return request({ url: `${base}/${userId}/password`, method: 'put', data: { password }, headers: { repeatSubmit: false } })
}
