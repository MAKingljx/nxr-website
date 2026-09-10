import request from '@/utils/request'

export const getCommercePolicyCatalog = () => request({ url: '/api/admin/commerce-policy/catalog', method: 'get' })
export const previewCommerceQuote = (params) => request({ url: '/api/admin/commerce-policy/quote-preview', method: 'get', params })
export const saveCommercePricePolicy = (data) => request({ url: '/api/admin/commerce-policy/price-policy', method: 'put', data })
export const saveCommerceShippingPolicy = (data) => request({ url: '/api/admin/commerce-policy/shipping-policy', method: 'put', data })
export const saveCommerceBusinessLine = (data) => request({ url: '/api/admin/commerce-policy/business-line', method: 'put', data })
export const saveCommerceWorkCenter = (data) => request({ url: '/api/admin/commerce-policy/work-center', method: 'put', data })
export const saveCommerceCustomerRouting = (data) => request({ url: '/api/admin/commerce-policy/customer-routing', method: 'put', data })
export const getCommerceStaffScopes = () => request({ url: '/api/admin/commerce-policy/staff-scopes', method: 'get' })
export const saveCommerceStaffScope = (data) => request({ url: '/api/admin/commerce-policy/staff-scope', method: 'put', data })
export const saveOwnedInventorySubmissionRouting = (data) => request({ url: '/api/admin/commerce-policy/submission-routing', method: 'put', data })
