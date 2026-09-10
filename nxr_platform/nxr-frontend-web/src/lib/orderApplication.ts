import { customerRequest, customerSession } from './customer'
import { apiBaseUrl } from './api'

export type ApplicationCard = {
  localId: string
  cardName: string
  year: string
  rarity: string
  productType: string
  category: string
  brandName: string
  setName: string
  cardNumber: string
  languageCode: string
  declaredValue: number | null
  itemNote: string
  frontPhotoId: number | null
  backPhotoId: number | null
}

export const emptyApplicationCard = (): ApplicationCard => ({
  localId: crypto.randomUUID(), cardName: '', year: '', rarity: '', productType: 'graded_card', category: 'trading_card',
  brandName: '', setName: '', cardNumber: '', languageCode: 'EN', declaredValue: null, itemNote: '',
  frontPhotoId: null, backPhotoId: null,
})

export type OrderPhoto = { id: number; originalFilename: string; mimeType: string; byteSize: number; widthPx: number; heightPx: number }
export const uploadOrderPhoto = (file: File) => {
  const body = new FormData(); body.set('file', file)
  return customerRequest<OrderPhoto>('/api/customer/order-photos', { method: 'POST', body })
}
export const removeUnusedOrderPhoto = (id: number) => customerRequest(`/api/customer/order-photos/${id}`, { method: 'DELETE' })
export async function orderPhotoUrl(id: number) {
  const token = customerSession.value?.token
  if (!token) throw new Error('Please sign in to see this image.')
  const response = await fetch(`${apiBaseUrl}/api/customer/order-photos/${id}`, { headers: { 'X-NXR-Customer-Token': token } })
  if (!response.ok) throw new Error('This image is unavailable.')
  return URL.createObjectURL(await response.blob())
}

export type AdmissionConfig = { paymentDeadlineHours: number; maxCardsPerOrder: number; termsVersion: string; termsText: string; turnaroundText?: string }
export type Admission = {
  orderId: number; orderNo: string; admissionStatus: string | null; legacyOrder: boolean; decisionNote: string | null;
  submittedAt: string | null; decidedAt: string | null; paymentDueAt: string | null; paymentDueAtIso: string | null; paymentExpired: boolean;
  termsVersion: string; termsText: string; turnaroundText?: string;
  acceptedTermsVersion: string | null; termsAcceptedAt: string | null; quoteAmount: number; quoteCurrency: string;
  canResubmit: boolean; canAcceptTerms: boolean; canPay: boolean; maxCardsPerOrder: number;
  admissionRevision: number; supplementalPhotoIds: number[];
  events: Array<{ id: number; eventCode: string; title: string; detail: string | null; actorType: string; createdAt: string }>;
}
export const fetchApplicationConfig = () => customerRequest<AdmissionConfig>('/api/customer/order-admission/config')
export const fetchAdmission = (orderNo: string) => customerRequest<Admission>(`/api/customer/orders/${encodeURIComponent(orderNo)}/admission`)
export const acceptOrderTerms = (orderNo: string, payload: { termsVersion: string; acceptedQuotedAmount: number; acceptedCurrency: string }) =>
  customerRequest<Admission>(`/api/customer/orders/${encodeURIComponent(orderNo)}/admission/accept-terms`, { method: 'POST', body: JSON.stringify(payload) })
export const resubmitApplication = (orderNo: string, note: string, supplementalPhotoIds: number[] = []) => customerRequest<Admission>(`/api/customer/orders/${encodeURIComponent(orderNo)}/admission/resubmit`, { method: 'POST', body: JSON.stringify({ note, supplementalPhotoIds }) })
