import { activeLocale, tx } from '@/i18n'
import { inject, ref, type InjectionKey } from 'vue'
import request from '@/utils/request'
const base = '/api/admin/agent'
export type AgentPage<T> = { items: T[]; total: number; page: number; pageSize: number }
export const emptyAgentPage = <T>(): AgentPage<T> => ({ items: [], total: 0, page: 1, pageSize: 20 })
export type AgentAddress = {
  contactName: string; phone: string; addressLine1: string; addressLine2: string;
  city: string; region: string; postalCode: string; country: string;
}
export const emptyAgentAddress = (): AgentAddress => ({ contactName: '', phone: '', addressLine1: '', addressLine2: '', city: '', region: '', postalCode: '', country: '' })
export type AgentClientInput = AgentAddress & { reference: string; displayName: string; email: string; notes: string; active: boolean }
export const emptyAgentClient = (): AgentClientInput => ({ ...emptyAgentAddress(), reference: '', displayName: '', email: '', notes: '', active: true })
export type AgentClient = AgentClientInput & { id: number; createdAt: string; updatedAt: string }
export type AgentEvent = { id: number; eventCode: string; note: string | null; inventoryCode: string | null; createdAt: string }
export type AgentIntake = {
  id: number; intakeNo: string; clientId: number; clientName: string; carrierName: string; trackingNumber: string;
  expectedCardCount: number; checkedInCardCount: number; exceptionCardCount: number; statusCode: string;
  batchId: number | null; batchNo: string | null; orderId: number | null; orderNo: string | null;
  notes: string | null; receivedAt: string | null; createdAt: string;
}
export type AgentCard = {
  id: number; intakeId: number; intakeNo: string; clientId: number; clientName: string; inventoryCode: string;
  cardName: string; languageCode: string; notes: string | null; statusCode: string; conditionNote: string | null;
  frontPhotoId: number | null; backPhotoId: number | null; orderItemId: number | null; orderId: number | null;
  orderNo: string | null; batchId: number | null; batchNo: string | null; batchStatusCode: string | null;
  returnShipmentId: number | null; gradingCertId: string | null; checkedInAt: string | null; returnedAt: string | null;
}
export type AgentShipment = {
  id: number; shipmentNo: string; clientId: number; clientName: string; carrierName: string; trackingNumber: string;
  statusCode: string; cardCount: number; address: AgentAddress; notes: string | null; shippedAt: string; deliveredAt: string | null;
}
export type AgentIntakeDetail = { intake: AgentIntake; cards: AgentCard[]; events: AgentEvent[] }
export type AgentShipmentDetail = { shipment: AgentShipment; cards: AgentCard[]; events: AgentEvent[] }
export type AgentClientDetail = { client: AgentClient; intakes: AgentIntake[]; shipments: AgentShipment[]; events: AgentEvent[] }
export type AgentIntakeInput = {
  clientId: number; carrierName: string; trackingNumber: string; expectedCardCount: number; notes: string;
  cards: Array<{ cardName: string; languageCode: string; notes: string }>;
}
export type AgentSubmissionInput = { intakeIds: number[]; returnAddressId: number; returnShippingOptionCode: string; currencyCode: string; batchName: string; quotedTotalAmount: number; quotedCurrencyCode: string }
export type AgentSubmission = { id: number; batchId: number; batchNo: string; intakeIds: number[]; createdAt: string }
export type AgentShipmentInput = { clientId: number; cardIds: number[]; carrierName: string; trackingNumber: string; address?: AgentAddress; note: string }

export type Company = { id: number; displayName: string; email: string; companyName: string }
export type AgentContext = { platformManager: boolean; company: Company | null }
export type AgentOperator = { sysUserId: number; userName: string; nickName: string; merchantCustomerId: number; companyName: string; active: boolean; updatedAt: string }
export type AgentOperatorCandidate = { sysUserId: number; userName: string; nickName: string }
export type CustomerAddress = { id: number; label: string; contactName: string; contactPhone: string; addressLine1: string; addressLine2: string | null; city: string; region: string | null; postalCode: string; country: string; defaultAddress: boolean }
export type OrderQuote = { currencyCode: string; cardCount: number; serviceFee: number; returnShippingFee: number; totalAmount: number; shippingSourceCode: string; shippingOptionCode: string; shippingDisplayName: string }
export type BatchQuote = { aggregate: OrderQuote; allocations: Array<{ reference: string; cardCount: number; totalAmount: number }> }
export type AgentWallet = { currencyCode: string; balance: number; updatedAt: string | null }
export type AgentRecharge = { id: number; rechargeNo: string; currencyCode: string; amount: number; statusCode: string; payerReference: string; proofReference: string; reviewNote?: string; createdAt: string }
export type AgentTransaction = { id: number; transactionTypeCode: string; directionCode: string; amount: number; balanceAfter: number; note?: string; createdAt: string }
export type AgentBatch = { id?: number; batchId?: number; batchNo: string; batchName: string; statusCode: string; createdAt: string; orders?: Array<{ orderNo: string; clientReference: string; clientDisplayName?: string; admissionStatus?: string; statusCode: string; totalCardCount?: number; cardCount?: number }>; shipments?: Array<{ id: number; directionCode: string; carrierName: string; trackingNumber: string; statusCode: string; deliveredAt?: string }> }
export type AgentOrder = { orderNo: string; statusCode: string; totalCardCount: number; totalAmount: number; currencyCode: string; items: Array<{ id: number; cardName: string; languageCode: string; gradingCertId?: string; frontPhotoId?: number; backPhotoId?: number; statusCode: string }>; timeline: Array<{ id: number; title: string; detail?: string; createdAt: string }> }
export type AgentAdmission = { admissionStatus: string; termsVersion: string; termsText: string; quoteAmount: number; quoteCurrency: string; canAcceptTerms: boolean; canPay: boolean; canResubmit: boolean; canResubmit: boolean; decisionNote?: string; paymentDueAtIso?: string; supplementalPhotoIds?: number[]; events: Array<{ id: number; title: string; detail?: string; createdAt: string }> }
type Query = Record<string, string | number | boolean | undefined>
function queryString(query: Query) { const params = new URLSearchParams(); Object.entries(query).forEach(([key,value]) => { if (value !== undefined && value !== '') params.set(key,String(value)) }); return params.toString() }
export function formatMoney(amount: number | string, currency: string) { return new Intl.NumberFormat(activeLocale(), { style: 'currency', currency }).format(Number(amount)) }
export function privateTrackingUrl(value: string) {
  if (/^\/track\/[A-Za-z0-9_-]+$/.test(value)) return value
  try {
    const url = new URL(value)
    if (['http:', 'https:'].includes(url.protocol) && !url.username && !url.password && /^\/track\/[A-Za-z0-9_-]+$/.test(url.pathname) && !url.search && !url.hash) return url.href
  } catch { /* A relative link must already point at the customer tracking route. */ }
  throw new Error(tx('The customer tracking URL is not configured correctly. Please contact NXR.'))
}
export function normalizeAgentReturnScan(value: string) {
  const scan = value.trim()
  if (/^[A-Za-z0-9_-]{1,64}$/.test(scan)) return scan
  // Inspect the raw path before any URL normalization can remove dot segments or decode input.
  if (scan.length <= 256 && !/[%\\\u0000-\u0020\u007f]/.test(scan) && !/\/\.{1,2}(?:\/|[?#]|$)/.test(scan)) {
    const link = scan.match(/^(?:https?:\/\/)?(?:www\.)?nxrgrading\.com(\/[^?#]*)(?:[?#].*)?$/i)
    const match = link?.[1]?.match(/^\/card\/([A-Za-z0-9_-]{1,64})\/?$/)
    if (match) return match[1]!
  }
  throw new Error(tx('Scan an inventory code, certificate ID or official NXR certificate QR code.'))
}

export function createAgentApi(companyId?: number) {
  const controller = new AbortController(), busy = ref(false)
  let active = true
  const headers = () => ({ ...(companyId ? { 'X-NXR-Agent-Id': String(companyId) } : {}), repeatSubmit: false })
  async function send<T>(path: string, config: Record<string, unknown> = {}): Promise<T> {
    if (!active) throw new Error(tx('The company workspace has been closed.'))
    try {
      const value = await request({ url: path, method: 'get', suppressErrorMessage: true, ...config, headers: { ...headers(), ...(config.headers as object || {}) }, signal: controller.signal })
      if (!active) throw new Error(tx('The company workspace has been closed.'))
      return value as T
    } catch (error: any) {
      throw new Error(error?.response?.data?.message || error?.response?.data?.msg || error?.message || tx('The request was not completed. Please try again.'))
    }
  }
  function scopedRequest<T>(path: string, init: RequestInit = {}) {
    const multipart = init.body instanceof FormData
    return send<T>(path, { method: (init.method || 'get').toLowerCase(), ...(init.body ? { data: multipart ? init.body : JSON.parse(String(init.body)) } : {}), ...(multipart ? { headers: { 'Content-Type': undefined } } : {}) })
  }
function post<T>(path: string, payload: object, requestKey: string) {
  return scopedRequest<T>(`${base}${path}`, { method: 'POST', body: JSON.stringify({ ...payload, requestKey }) })
}
const fetchAgentClients = (query: Query = {}) => scopedRequest<AgentPage<AgentClient>>(`${base}/clients?${queryString(query)}`)
const fetchAgentClient = (id: number) => scopedRequest<AgentClientDetail>(`${base}/clients/${id}`)
const createAgentClient = (payload: AgentClientInput, key: string) => post<AgentClient>('/clients', payload, key)
const updateAgentClient = (id: number, payload: AgentClientInput) => scopedRequest<AgentClient>(`${base}/clients/${id}`, { method: 'PUT', body: JSON.stringify(payload) })
const fetchAgentIntakes = (query: Query = {}) => scopedRequest<AgentPage<AgentIntake>>(`${base}/intakes?${queryString(query)}`)
const fetchAgentIntake = (id: number) => scopedRequest<AgentIntakeDetail>(`${base}/intakes/${id}`)
const createAgentIntake = (payload: AgentIntakeInput, key: string) => post<AgentIntakeDetail>('/intakes', payload, key)
const receiveAgentIntake = (id: number, payload: { note: string }, key: string) => post<AgentIntakeDetail>(`/intakes/${id}/receive`, payload, key)
const checkInAgentCard = (id: number, payload: { inventoryCode: string; conditionNote: string; hasException: boolean }, key: string) => post<AgentIntakeDetail>(`/intakes/${id}/check-in`, payload, key)
const fetchAgentCards = (query: Query = {}) => scopedRequest<AgentPage<AgentCard>>(`${base}/cards?${queryString(query)}`)
const fetchAgentEvents = (query: Query = {}) => scopedRequest<AgentPage<AgentEvent>>(`${base}/events?${queryString(query)}`)
const createAgentSubmission = (payload: AgentSubmissionInput, key: string) => post<AgentSubmission>('/submissions', payload, key)
const checkAgentReturn = (id: number, payload: { inventoryCode: string; note: string }, key: string) => post<AgentCard>(`/cards/${id}/return-check`, payload, key)
const fetchAgentShipments = (query: Query = {}) => scopedRequest<AgentPage<AgentShipment>>(`${base}/return-shipments?${queryString(query)}`)
const fetchAgentShipment = (id: number) => scopedRequest<AgentShipmentDetail>(`${base}/return-shipments/${id}`)
const createAgentShipment = (payload: AgentShipmentInput, key: string) => post<AgentShipmentDetail>('/return-shipments', payload, key)
const deliverAgentShipment = (id: number, payload: { note: string }, key: string) => post<AgentShipmentDetail>(`/return-shipments/${id}/delivered`, payload, key)
const uploadAgentCardPhoto = (id: number, side: 'front' | 'back', file: File) => {
  const body = new FormData(); body.set('file', file)
  return scopedRequest<AgentCard>(`${base}/cards/${id}/photos?side=${side}`, { method: 'POST', body })
}


  const fetchContext = () => send<AgentContext>(`${base}/context`)
  const fetchCompanies = (query: Query = {}) => send<AgentPage<Company>>(`${base}/companies?${queryString(query)}`)
  const fetchOperators = (query: Query = {}) => send<AgentPage<AgentOperator>>(`${base}/operators?${queryString(query)}`)
  const fetchOperatorCandidates = (query: Query = {}) => send<AgentPage<AgentOperatorCandidate>>(`${base}/operator-candidates?${queryString(query)}`)
  const updateOperator = (id: number, data: { merchantCustomerId: number; active: boolean }) => send<AgentOperator>(`${base}/operators/${id}`, { method: 'put', data })
  const fetchApplicationConfig = () => send<{ maxCardsPerOrder: number }>(`${base}/order-admission/config`)
  const fetchCustomerAddresses = () => send<CustomerAddress[]>(`${base}/addresses`)
  const saveAddress = (id: number | null, data: object, key: string) => send<CustomerAddress>(`${base}/addresses${id ? `/${id}` : ''}`, { method: id ? 'put' : 'post', data: { ...data, requestKey: key } })
  const deleteAddress = (id: number) => send(`${base}/addresses/${id}`, { method: 'delete' })
  const fetchServicePrices = () => send<Array<{ currencyCode: string }>>(`${base}/service-prices`)
  const fetchShippingOptions = (country: string, currencyCode: string) => send<Array<{ optionCode: string; displayName: string }>>(`${base}/shipping-options?${queryString({country,currencyCode})}`)
  const fetchOrderQuote = (country: string, currency: string, count: number, shippingOptionCode: string) => send<OrderQuote>(`${base}/commerce/quote-preview?${queryString({ customerId: companyId, country,currency,count,shippingOptionCode })}`)
  const fetchBatchQuote = (country: string, currency: string, shippingOptionCode: string, parts: Array<{reference: string; cardCount: number}>) => send<BatchQuote>(`${base}/commerce/batch-quote-preview`, { method: 'post', data: {customerId:companyId,country,currency,shippingOptionCode,parts} })
  const fetchPhoto = (id: number) => send<Blob>(`${base}/order-photos/${id}`, { responseType: 'blob' })
  const fetchMerchantProfile = () => send<{ companyName: string; contactName: string }>(`${base}/merchant/profile`)
  const saveMerchantProfile = (data: object) => send(`${base}/merchant/profile`, { method: 'put', data })
  const fetchWallets = () => send<AgentWallet[]>(`${base}/merchant/wallets`)
  const fetchTransactions = (currencyCode: string, page = 1) => send<AgentPage<AgentTransaction>>(`${base}/merchant/wallet-transactions?${queryString({currencyCode,page,pageSize:20})}`)
  const fetchRecharges = (page = 1) => send<AgentPage<AgentRecharge>>(`${base}/merchant/wallet-recharges?page=${page}&pageSize=20`)
  const requestRecharge = (data: object, requestKey: string) => send<AgentRecharge>(`${base}/merchant/wallet-recharges`, { method: 'post', data: {...data,requestKey} })
  const fetchBatches = (page = 1) => send<AgentPage<AgentBatch>>(`${base}/merchant/batches?page=${page}&pageSize=20`)
  const fetchBatch = (batchNo: string) => send<AgentBatch>(`${base}/merchant/batches/${encodeURIComponent(batchNo)}`)
  const rotateTrackingLink = (batchNo: string, orderNo: string) => send<{trackingToken:string;trackingUrl:string}>(`${base}/merchant/batches/${encodeURIComponent(batchNo)}/orders/${encodeURIComponent(orderNo)}/tracking-token/rotate`,{method:'post'})
  const revokeTrackingLink = (batchNo: string, orderNo: string) => send(`${base}/merchant/batches/${encodeURIComponent(batchNo)}/orders/${encodeURIComponent(orderNo)}/tracking-token`,{method:'delete'})
  const addBatchInbound = (batchNo: string, data: object, key: string) => post<AgentBatch>(`/merchant/batches/${encodeURIComponent(batchNo)}/inbound-shipment`, data, key)
  const fetchOrder = (orderNo: string) => send<AgentOrder>(`${base}/orders/${encodeURIComponent(orderNo)}`)
  const fetchAdmission = (orderNo: string) => send<AgentAdmission>(`${base}/orders/${encodeURIComponent(orderNo)}/admission`)
  const acceptOrderTerms = (orderNo: string, data: object, key: string) => post<AgentAdmission>(`/orders/${encodeURIComponent(orderNo)}/admission/accept-terms`, data, key)
  const payFromWallet = (orderNo: string, idempotencyKey: string) => send<AgentOrder>(`${base}/orders/${encodeURIComponent(orderNo)}/wallet-payment`, { method: 'post', data: {idempotencyKey} })
  const fetchPackingSlip = (orderNo: string) => send<{ orderNo: string; intakeCode: string; packingSlipCode: string; totalCardCount: number; qrPayload: string; packingInstructions: string[] }>(`${base}/orders/${encodeURIComponent(orderNo)}/packing-slip`)
  const resubmitOrder = (orderNo: string, data: { note: string; supplementalPhotoIds: number[] }, key: string) => post<AgentAdmission>(`/orders/${encodeURIComponent(orderNo)}/admission/resubmit`,data,key)
  const uploadSupplementalPhoto = (file: File) => { const body = new FormData();body.set('file',file);return scopedRequest<{id:number}>(`${base}/order-photos`,{method:'POST',body}) }

  return { companyId, busy, isActive: () => active, dispose: () => { active = false; controller.abort(); busy.value = false }, fetchAgentClients, fetchAgentClient, createAgentClient, updateAgentClient, fetchAgentIntakes, fetchAgentIntake, createAgentIntake, receiveAgentIntake, checkInAgentCard, fetchAgentCards, fetchAgentEvents, createAgentSubmission, checkAgentReturn, fetchAgentShipments, fetchAgentShipment, createAgentShipment, deliverAgentShipment, uploadAgentCardPhoto, fetchContext, fetchCompanies, fetchOperators, fetchOperatorCandidates, updateOperator, fetchApplicationConfig, fetchCustomerAddresses, saveAddress, deleteAddress, fetchServicePrices, fetchShippingOptions, fetchOrderQuote, fetchBatchQuote, fetchPhoto, fetchMerchantProfile, saveMerchantProfile, fetchWallets, fetchTransactions, fetchRecharges, requestRecharge, fetchBatches, fetchBatch, rotateTrackingLink, revokeTrackingLink, addBatchInbound, fetchOrder, fetchAdmission, acceptOrderTerms, payFromWallet, fetchPackingSlip, resubmitOrder, uploadSupplementalPhoto }
}

export type AgentApi = ReturnType<typeof createAgentApi>
export const AgentApiKey: InjectionKey<AgentApi> = Symbol('agent-company-api')
export function useAgentApi() { const api = inject(AgentApiKey); if (!api) throw new Error(tx('Sub-agent company information is not ready.')); return api }
// A failed operation keeps its key only while its content remains identical.
export function useAgentActions() {
  const api = useAgentApi()
  const busy = api.busy, error = ref(''), success = ref('')
  const attempts = new Map<string, { fingerprint: string; key: string }>()
  async function run<T>(name: string, payload: object, request: (key: string) => Promise<T>, apply: (value: T) => void, message: string) {
    if (busy.value || !api.isActive()) return false
    const fingerprint = JSON.stringify(payload)
    let attempt = attempts.get(name)
    if (!attempt || attempt.fingerprint !== fingerprint) {
      attempt = { fingerprint, key: crypto.randomUUID() }
      attempts.set(name, attempt)
    }
    busy.value = true; error.value = ''; success.value = ''
    try {
      const value = await request(attempt.key)
      if (!api.isActive()) return false
      attempts.delete(name)
      apply(value)
      success.value = message
      return true
    } catch (e) {
      error.value = e instanceof Error ? e.message : tx('The operation was not completed. Please try again.')
      return false
    } finally { if (api.isActive()) busy.value = false }
  }
  return { busy, error, success, run }
}

const statusLabels: Record<string, string> = {
  expected: 'Awaiting receipt', received: 'Awaiting inventory check', ready: 'Intake complete', exception: 'Exception', in_stock: 'In inventory',
  submitted: 'Submitted for grading', returned: 'Returned card checked', return_shipped: 'Shipped to customer', shipped: 'Shipped', delivered: 'Delivered',
  draft: 'Pending', awaiting_inbound: 'Awaiting shipment to NXR', inbound_shipped: 'In transit to NXR', grading: 'Grading',
  outbound_shipped: 'Returned by NXR', completed: 'Grading completed', cancelled: 'Cancelled',
  admission_review: 'Application under review', pending_review: 'Application under review', needs_information: 'More information required', approved: 'Application approved',
  terms_confirmation: 'Awaiting terms acceptance', awaiting_payment: 'Awaiting payment', payment_review: 'Verifying payment', payment_expired: 'Payment overdue',
  payment_exception: 'Payment exception under review', confirmed: 'Confirmed', rejected: 'Rejected', pending: 'Awaiting payment verification', open: 'Batch in preparation',
  intake_exception: 'NXR intake exception', review: 'Grading review', quality_check: 'Quality check', quality_hold: 'Quality review',
  in_transit: 'In transit', processing: 'Processing', expired: 'Expired',
}
export const agentStatusLabel = (code: string) => tx(statusLabels[code] || code)
const eventLabels: Record<string, string> = {
  client_created: 'Customer record created', client_updated: 'Customer record updated', client_archived: 'Archive customer', client_reactivated: 'Restore customer',
  intake_created: 'Intake registered', intake_received: 'Intake received', card_checked_in: 'Card checked into inventory', card_exception: 'Card exception recorded',
  card_photo_uploaded: 'Upload card photo', submission_created: 'Create submission batch', card_return_checked: 'Check returned cards',
  return_shipment_created: 'Return to customer', return_shipment_delivered: 'Customer delivery confirmed',
  photo_added: 'Upload card photo', intake_submitted: 'Intake submitted for grading', card_return_shipped: 'Card shipped to customer',
  shipment_created: 'Customer return recorded', client_received_card: 'Customer received cards', shipment_delivered: 'Return delivered to customer',
}
export const agentEventLabel = (code: string) => tx(eventLabels[code] || code)
export const agentDateLabel = (value: string | null) => value ? new Date(value).toLocaleString(activeLocale()) : '—'
export const agentAddressLabel = (address: AgentAddress) => [address.country, address.region, address.city, address.addressLine1, address.addressLine2, address.postalCode].filter(Boolean).join(' · ')
