import { customerRequest } from './customer'
export type BatchOrder = { orderId: number; orderNo: string; clientReference: string; intakeNo?: string | null; admissionStatus?: string | null; clientDisplayName?: string; statusCode: string; cardCount?: number; totalCardCount?: number }
export type MerchantBatch = { id?: number; batchId?: number; batchNo: string; batchName: string; statusCode: string; createdAt: string; orders?: BatchOrder[]; shipments?: Array<{ directionCode: string; carrierName: string; trackingNumber: string }> }
export type BatchImportResult = {
  batchId: number; batchNo: string; statusCode: string; acceptedRows: number; rejectedRows: number;
  rows: Array<{ rowNo: number; statusCode: string; orderId: number | null; orderNo: string | null; clientReference: string; trackingToken?: string; trackingUrl?: string; errorMessage: string | null }>
}
export const createMerchantBatch = (payload: Record<string, unknown>) => customerRequest<BatchImportResult>('/api/customer/merchant/batches', { method: 'POST', body: JSON.stringify(payload) })
export const fetchMerchantBatches = () => customerRequest<{ items: MerchantBatch[]; total: number }>('/api/customer/merchant/batches?page=1&pageSize=100')
export const fetchMerchantBatch = (batchNo: string) => customerRequest<MerchantBatch>(`/api/customer/merchant/batches/${encodeURIComponent(batchNo)}`)
export const rotateTrackingLink = (batchNo: string, orderNo: string) => customerRequest<{ trackingToken: string; trackingUrl: string }>(`/api/customer/merchant/batches/${encodeURIComponent(batchNo)}/orders/${encodeURIComponent(orderNo)}/tracking-token/rotate`, { method: 'POST' })
export const addBatchInbound = (batchNo: string, carrierName: string, trackingNumber: string) => customerRequest(`/api/customer/merchant/batches/${encodeURIComponent(batchNo)}/inbound-shipment`, { method: 'POST', body: JSON.stringify({ direction: 'inbound', directionCode: 'inbound', carrierName, trackingNumber }) })
export type PrivateReturnCard = { cardName: string; certId: string | null; statusCode: string }
export type PrivateReturnParcel = {
  parcelNo: string; carrierName: string; trackingNumber: string; statusCode: string;
  shippedAt: string | null; deliveredAt: string | null; cards: PrivateReturnCard[];
}
export type PrivateOrderCard = {
  id: number; cardName: string; brandName: string | null; setName: string | null; cardNumber: string | null;
  languageCode: string | null; productType: string | null; category: string | null; statusCode: string;
  certificateId: string | null; hasFrontPhoto: boolean; hasBackPhoto: boolean
}
export type PrivateOrderTracking = {
  orderNo: string; clientReference: string; admissionStatus?: string | null; statusCode: string;
  cardCount?: number; totalCardCount?: number;
  timeline: Array<{ eventCode?: string; title: string; createdAt: string; statusCode?: string }>;
  shipments: Array<{ directionCode: string; carrierName: string; trackingNumber: string; statusCode: string; shippedAt?: string; deliveredAt?: string }>;
  // The public endpoint scopes each parcel's cards to this order, including a
  // parcel that physically carries cards belonging to other orders.
  returnParcels?: PrivateReturnParcel[];
  cards?: PrivateOrderCard[];
}
export const verifyPrivateOrderTracking = (token: string, contact: string) => customerRequest<{ accessToken: string; expiresInSeconds: number }>('/api/public/merchant-order-tracking/verify', { method: 'POST', body: JSON.stringify({ token, contact }), cache: 'no-store', credentials: 'omit', referrerPolicy: 'no-referrer' }, false)
export const fetchPrivateOrderTracking = (token: string, accessToken: string) => customerRequest<PrivateOrderTracking>('/api/public/merchant-order-tracking/order', { headers: { 'X-NXR-Tracking-Token': token, 'X-NXR-Tracking-Access': accessToken }, cache: 'no-store', credentials: 'omit', referrerPolicy: 'no-referrer' }, false)
export const localTrackingUrl = (token: string) => `${window.location.origin}${import.meta.env.BASE_URL.replace(/\/$/, '')}/track#${encodeURIComponent(token)}`

export const revokeTrackingLink = (batchNo: string, orderNo: string) => customerRequest(`/api/customer/merchant/batches/${encodeURIComponent(batchNo)}/orders/${encodeURIComponent(orderNo)}/tracking-token`, { method: 'DELETE' })
