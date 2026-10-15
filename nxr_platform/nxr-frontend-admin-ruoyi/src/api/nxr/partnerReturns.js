import request from '@/utils/request'

const root = '/api/admin/partner-returns'
export const listPartnerReturnCompanies = params => request({ url: root + '/companies', method: 'get', params })
export const listPartnerReturnCards = params => request({ url: root + '/cards', method: 'get', params })
export const checkPartnerReturnCard = (id, data) => request({ url: root + '/cards/' + id + '/check', method: 'post', data })
export const listPartnerReturns = params => request({ url: root, method: 'get', params })
export const getPartnerReturn = id => request({ url: root + '/' + id, method: 'get' })
export const createPartnerReturn = data => request({ url: root, method: 'post', data, headers: { repeatSubmit: false } })
export const deliverPartnerReturn = id => request({ url: root + '/' + id + '/delivered', method: 'post' })
