import request from '@/utils/request'

export const getOrderWorkbench = (orderId) => request({ url: `/api/admin/orders/${orderId}/workbench`, method: 'get' })
export const startOrderWorkbench = (orderId) => request({ url: `/api/admin/orders/${orderId}/workbench/start`, method: 'post' })
export const scanOrderWorkbench = (orderId, data) => request({ url: `/api/admin/orders/${orderId}/workbench/scan`, method: 'post', data, headers: { repeatSubmit: false } })
export const completeOrderPackingCheck = (orderId, data) => request({ url: `/api/admin/orders/${orderId}/workbench/packing-check`, method: 'post', data })
export const exportOrderLabels = (orderId, data) => request({ url: `/api/admin/orders/${orderId}/workbench/label-export`, method: 'post', data, responseType: 'blob' })
export const exportOrderManifest = (orderId, data) => request({ url: `/api/admin/orders/${orderId}/workbench/manifest-export`, method: 'post', data, responseType: 'blob' })
export const createOrderItemSubmission = (orderId, itemId, data) => request({ url: `/api/admin/orders/${orderId}/items/${itemId}/submission`, method: 'post', data })

export const listMerchantBatches = (params) => request({ url: '/api/admin/merchant-batches', method: 'get', params })
export const getMerchantBatch = (batchId) => request({ url: `/api/admin/merchant-batches/${batchId}`, method: 'get' })
export const createMerchantBatchOutbound = (batchId, data) => request({ url: `/api/admin/merchant-batches/${batchId}/outbound-shipment`, method: 'post', data })
export const markMerchantBatchShipmentDelivered = (batchId, shipmentId) => request({ url: `/api/admin/merchant-batches/${batchId}/shipments/${shipmentId}/delivered`, method: 'post' })
