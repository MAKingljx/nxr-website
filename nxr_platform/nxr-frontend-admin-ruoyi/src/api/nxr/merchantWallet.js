import request from '@/utils/request'
const base = id => `/api/admin/customers/${id}`
export const getMerchantProfile = id => request({ url: `${base(id)}/merchant-profile`, method: 'get' })
export const saveMerchantProfile = (id, data) => request({ url: `${base(id)}/merchant-profile`, method: 'put', data })
export const getWallets = id => request({ url: `${base(id)}/wallets`, method: 'get' })
export const getWalletTransactions = (id, params) => request({ url: `${base(id)}/wallet-transactions`, method: 'get', params })
export const getWalletRecharges = (id, params) => request({ url: `${base(id)}/wallet-recharges`, method: 'get', params })
export const reviewWalletRecharge = (id, rechargeId, approved, data) => request({ url: `${base(id)}/wallet-recharges/${rechargeId}/${approved ? 'confirm' : 'reject'}`, method: 'post', data })
