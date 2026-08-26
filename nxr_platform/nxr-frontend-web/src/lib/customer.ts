import { computed, ref } from 'vue'
import { apiBaseUrl, type ProductType } from './api'

const customerSessionKey = 'nxr_customer_session'

export type CustomerProfile = {
  id: number
  email: string
  displayName: string
  mobile: string | null
  accountTypeCode: 'customer' | 'merchant'
  createdAt: string
  lastLoginAt: string | null
}

export type CustomerSession = {
  token: string
  expiresAt: string
  customer: CustomerProfile
}

export type CustomerCard = {
  certId: string
  productType?: ProductType | 'label_product' | null
  vintageClassification?: string | null
  merchDescription?: string | null
  cardName: string
  brandName: string
  yearLabel: string
  setName: string
  cardNumber: string
  finalGradeValue: number | null
  finalGradeLabel: string | null
  frontImageUrl: string | null
  visibilityCode: string
  note: string | null
  boundAt: string
}

export type CardCommunity = {
  ownership: {
    certId: string
    customerId: number
    ownerLabel: string
    visibilityCode: string
    note: string | null
    boundAt: string
  } | null
  timeline: Array<{
    id: number
    eventTypeCode: string
    visibilityCode: string
    fromLabel: string
    toLabel: string
    message: string | null
    createdAt: string
  }>
}

export type OrderItem = {
  id: number
  itemNo: number
  cardName: string
  brandName: string | null
  setName: string | null
  cardNumber: string | null
  languageCode: string | null
  declaredValue: number | null
  itemNote: string | null
  statusCode: string
  gradingSubmissionId: number | null
  gradingCertId: string | null
  gradingStatusCode: string | null
}

export type PaymentRecord = {
  id: number
  orderId: number
  directionCode: string
  paymentTypeCode: string
  paymentNo: string | null
  providerCode: string
  methodLabel: string | null
  statusCode: string
  amount: number
  currencyCode: string
  payerReference: string | null
  proofReference: string | null
  paymentUrl: string | null
  qrPayload: string | null
  providerTransactionId: string | null
  confirmedByUserId: number | null
  submittedAt: string | null
  confirmedAt: string | null
  callbackReceivedAt: string | null
  note: string | null
  createdAt: string
}

export type ShipmentRecord = {
  id: number
  orderId: number
  directionCode: string
  shippingOptionCode: string | null
  shippingOptionName: string | null
  carrierName: string
  trackingNumber: string
  statusCode: string
  shippedAt: string
  deliveredAt: string | null
  note: string | null
}

export type GradingOrder = {
  id: number
  orderNo: string
  statusCode: string
  serviceLevelCode: string
  returnShippingOptionCode: string | null
  returnShippingOptionName: string | null
  totalCardCount: number
  serviceFee: number
  returnShippingFee: number
  totalAmount: number
  currencyCode: string
  contactName: string
  contactPhone: string
  returnAddressLine1: string
  returnAddressLine2: string | null
  returnCity: string
  returnRegion: string | null
  returnPostalCode: string
  returnCountry: string
  customerNote: string | null
  internalNote: string | null
  intakeCode: string | null
  packingSlipCode: string | null
  shippingLabelCreatedAt: string | null
  createdAt: string
  updatedAt: string
  items: OrderItem[]
  payments: PaymentRecord[]
  shipments: ShipmentRecord[]
  timeline: Array<{
    id: number
    eventCode: string
    title: string
    detail: string | null
    statusCode: string | null
    actorTypeCode: string
    createdAt: string
  }>
}

export type CustomerAddress = {
  id: number
  customerId: number
  label: string
  contactName: string
  contactPhone: string
  addressLine1: string
  addressLine2: string | null
  city: string
  region: string | null
  postalCode: string
  country: string
  defaultAddress: boolean
  createdAt: string
  updatedAt: string
}

export type ShippingOption = {
  id: number
  optionCode: string
  displayName: string
  description: string | null
  countryScope: string
  currencyCode: string
  priceAmount: number
  sortOrder: number
  active: boolean
}

export type PackingSlip = {
  orderNo: string
  intakeCode: string
  packingSlipCode: string
  qrPayload: string
  totalCardCount: number
  languageGroups: Array<{ languageCode: string; quantity: number }>
  packingInstructions: string[]
}

export type OrderException = {
  id: number
  exceptionTypeCode: string
  statusCode: string
  title: string
  detail: string | null
  resolutionNote: string | null
  visibleToCustomer: boolean
  createdAt: string
  resolvedAt: string | null
}

export type TrackingEvent = {
  id: number
  shipmentId: number
  directionCode: string
  eventCode: string
  eventTitle: string
  locationLabel: string | null
  eventDetail: string | null
  eventTime: string
}

export type TicketMessage = {
  id: number
  actorTypeCode: string
  actorCustomerId: number | null
  actorAdminUserId: number | null
  message: string
  attachmentReference: string | null
  createdAt: string
}

export type SupportTicket = {
  id: number
  ticketNo: string
  orderId: number
  customerId: number
  categoryCode: string
  statusCode: string
  subject: string
  assignedUserId: number | null
  createdAt: string
  updatedAt: string
  closedAt: string | null
  messages: TicketMessage[]
}

export type ShippingChange = {
  id: number
  orderId: number
  ticketId: number | null
  oldOptionCode: string
  oldOptionName: string
  oldPriceAmount: number
  newOptionCode: string
  newOptionName: string
  newPriceAmount: number
  currencyCode: string
  differenceAmount: number
  statusCode: string
  reason: string | null
  paymentId: number | null
  reviewedByUserId: number | null
  reviewedAt: string | null
  settledAt: string | null
  createdAt: string
}

export type CustomerOrderOperations = {
  packingSlip: PackingSlip | null
  exceptions: OrderException[]
  trackingEvents: TrackingEvent[]
  tickets: SupportTicket[]
  shippingChanges: ShippingChange[]
  effectiveShippingOption: {
    optionCode: string
    displayName: string
    priceAmount: number
    currencyCode: string
  }
}

export type GradingOrderList = {
  items: Array<Pick<GradingOrder, 'id' | 'orderNo' | 'statusCode' | 'serviceLevelCode' | 'totalCardCount' | 'totalAmount' | 'currencyCode' | 'createdAt' | 'updatedAt'>>
  page: number
  pageSize: number
  total: number
}

function readStoredSession(): CustomerSession | null {
  if (typeof window === 'undefined') return null
  try {
    const raw = window.localStorage.getItem(customerSessionKey)
    if (!raw) return null
    const parsed = JSON.parse(raw) as CustomerSession
    return parsed.token && parsed.customer ? parsed : null
  } catch {
    return null
  }
}

export const customerSession = ref<CustomerSession | null>(readStoredSession())
export const isCustomerSignedIn = computed(() => customerSession.value !== null)

function storeSession(nextSession: CustomerSession | null) {
  customerSession.value = nextSession
  if (typeof window === 'undefined') return
  if (nextSession) {
    window.localStorage.setItem(customerSessionKey, JSON.stringify(nextSession))
  } else {
    window.localStorage.removeItem(customerSessionKey)
  }
}

async function readError(response: Response) {
  try {
    const payload = await response.json()
    return payload.message || payload.error || `Request failed with ${response.status}`
  } catch {
    return `Request failed with ${response.status}`
  }
}

export async function customerRequest<T>(path: string, init: RequestInit = {}, requiresSession = true): Promise<T> {
  const headers = new Headers(init.headers)
  if (!headers.has('Content-Type') && init.body) headers.set('Content-Type', 'application/json')
  if (requiresSession) {
    const token = customerSession.value?.token
    if (!token) throw new Error('Please sign in to continue.')
    headers.set('X-NXR-Customer-Token', token)
  }
  const response = await fetch(`${apiBaseUrl}${path}`, { ...init, headers })
  if (!response.ok) {
    if (response.status === 401 && requiresSession) storeSession(null)
    throw new Error(await readError(response))
  }
  const payload = await response.json() as T & { code?: number; msg?: string }
  if (typeof payload.code === 'number' && payload.code !== 200) {
    throw new Error(payload.msg || 'Request failed')
  }
  return payload
}

export async function registerCustomer(payload: { email: string; password: string; displayName: string; mobile?: string }) {
  const response = await customerRequest<CustomerSession>('/api/customer/auth/register', {
    method: 'POST',
    body: JSON.stringify(payload),
  }, false)
  storeSession(response)
  return response
}

export async function loginCustomer(payload: { email: string; password: string }) {
  const response = await customerRequest<CustomerSession>('/api/customer/auth/login', {
    method: 'POST',
    body: JSON.stringify(payload),
  }, false)
  storeSession(response)
  return response
}

export async function logoutCustomer() {
  try {
    if (customerSession.value) await customerRequest('/api/customer/auth/logout', { method: 'POST' })
  } finally {
    storeSession(null)
  }
}

export async function refreshCustomerSession() {
  if (!customerSession.value) return null
  try {
    const profile = await customerRequest<CustomerProfile>('/api/customer/auth/me')
    const nextSession = { ...customerSession.value, customer: profile }
    storeSession(nextSession)
    return nextSession
  } catch {
    return null
  }
}

export function fetchCardCommunity(certId: string) {
  return customerRequest<CardCommunity>(`/api/customer/cards/${encodeURIComponent(certId)}/community`, {}, false)
}

export function fetchCustomerCards() {
  return customerRequest<CustomerCard[]>('/api/customer/cards')
}

export function claimCustomerCard(certId: string, payload: { visibility: string; note: string }) {
  return customerRequest<CardCommunity>(`/api/customer/cards/${encodeURIComponent(certId)}/claim`, {
    method: 'POST', body: JSON.stringify(payload),
  })
}

export function transferCustomerCard(certId: string, payload: { recipientEmail: string; visibility: string; message: string }) {
  return customerRequest<CardCommunity>(`/api/customer/cards/${encodeURIComponent(certId)}/transfer`, {
    method: 'POST', body: JSON.stringify(payload),
  })
}

export function createGradingOrder(payload: Record<string, unknown>) {
  return customerRequest<GradingOrder>('/api/customer/orders', { method: 'POST', body: JSON.stringify(payload) })
}

export function fetchCustomerAddresses() {
  return customerRequest<CustomerAddress[]>('/api/customer/addresses')
}

export function createCustomerAddress(payload: Record<string, unknown>) {
  return customerRequest<CustomerAddress>('/api/customer/addresses', { method: 'POST', body: JSON.stringify(payload) })
}

export function updateCustomerAddress(addressId: number, payload: Record<string, unknown>) {
  return customerRequest<CustomerAddress>(`/api/customer/addresses/${addressId}`, { method: 'PUT', body: JSON.stringify(payload) })
}

export function deleteCustomerAddress(addressId: number) {
  return customerRequest<{ success: boolean }>(`/api/customer/addresses/${addressId}`, { method: 'DELETE' })
}

export function fetchShippingOptions(country = '') {
  const query = country.trim() ? `?country=${encodeURIComponent(country.trim())}` : ''
  return customerRequest<ShippingOption[]>(`/api/customer/shipping-options${query}`, {}, false)
}

export function fetchServicePrice() {
  return customerRequest<{
    priceCode: string
    displayName: string
    unitPrice: number
    currencyCode: string
    versionNo: number
  }>('/api/customer/service-price', {}, false)
}

export function fetchCustomerOrders() {
  return customerRequest<GradingOrderList>('/api/customer/orders')
}

export function fetchCustomerOrder(orderNo: string) {
  return customerRequest<GradingOrder>(`/api/customer/orders/${encodeURIComponent(orderNo)}`)
}

export function submitPaymentProof(orderNo: string, payload: { provider: string; payerReference: string; proofReference: string }) {
  return customerRequest<GradingOrder>(`/api/customer/orders/${encodeURIComponent(orderNo)}/payment-proof`, {
    method: 'POST', body: JSON.stringify(payload),
  })
}

export function createPaymentSession(orderNo: string, provider: string) {
  return customerRequest<{
    paymentId: number
    paymentNo: string
    provider: string
    paymentUrl: string
    qrPayload: string
    amount: number
    currencyCode: string
  }>(`/api/customer/orders/${encodeURIComponent(orderNo)}/payment-session`, {
    method: 'POST', body: JSON.stringify({ provider }),
  })
}

export function addInboundShipment(orderNo: string, payload: { direction: string; carrierName: string; trackingNumber: string; note: string }) {
  return customerRequest<GradingOrder>(`/api/customer/orders/${encodeURIComponent(orderNo)}/inbound-shipment`, {
    method: 'POST', body: JSON.stringify(payload),
  })
}

export function fetchOrderOperations(orderNo: string) {
  return customerRequest<CustomerOrderOperations>(`/api/customer/orders/${encodeURIComponent(orderNo)}/operations`)
}

export function createSupportTicket(orderNo: string, payload: Record<string, unknown>) {
  return customerRequest<SupportTicket>(`/api/customer/orders/${encodeURIComponent(orderNo)}/tickets`, {
    method: 'POST', body: JSON.stringify(payload),
  })
}

export function addSupportTicketMessage(orderNo: string, ticketId: number, payload: Record<string, unknown>) {
  return customerRequest<SupportTicket>(`/api/customer/orders/${encodeURIComponent(orderNo)}/tickets/${ticketId}/messages`, {
    method: 'POST', body: JSON.stringify(payload),
  })
}

export function requestShippingChange(orderNo: string, payload: Record<string, unknown>) {
  return customerRequest<ShippingChange>(`/api/customer/orders/${encodeURIComponent(orderNo)}/shipping-change`, {
    method: 'POST', body: JSON.stringify(payload),
  })
}

export function createMerchantOrders(payload: Record<string, unknown>) {
  return customerRequest<{
    jobId: number
    acceptedRows: number
    rejectedRows: number
    rows: Array<{ rowNo: number; statusCode: string; orderId: number | null; errorMessage: string | null }>
  }>('/api/customer/merchant/orders/bulk', { method: 'POST', body: JSON.stringify(payload) })
}

export async function downloadMerchantOrderTemplate() {
  const token = customerSession.value?.token
  if (!token) throw new Error('Please sign in to continue.')
  const response = await fetch(`${apiBaseUrl}/api/customer/merchant/orders/template`, {
    headers: { 'X-NXR-Customer-Token': token },
  })
  if (!response.ok) throw new Error(await readError(response))
  return response.text()
}
