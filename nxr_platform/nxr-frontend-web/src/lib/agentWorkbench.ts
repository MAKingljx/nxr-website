import { ref } from 'vue'
import { customerRequest } from './customer'

const base = '/api/customer/agent'
export function normalizeAgentReturnScan(value: string): string {
  const raw = value.trim()
  try {
    const url = new URL(/^(www\.)?nxrgrading\.com\//i.test(raw) ? `https://${raw}` : raw)
    if (['https:', 'http:'].includes(url.protocol) && ['nxrgrading.com', 'www.nxrgrading.com'].includes(url.hostname.toLowerCase())
      && !url.username && !url.password && !url.port && !/%|\/\.\.?\//.test(raw)) {
      const match = /^\/card\/([A-Za-z0-9_-]{1,64})\/?$/.exec(url.pathname)
      if (match) return match[1]!
    }
  } catch { /* A bare inventory code or certificate is already suitable. */ }
  return raw
}
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
type Query = Record<string, string | number | boolean | undefined>
function queryString(query: Query) {
  const params = new URLSearchParams()
  Object.entries(query).forEach(([key, value]) => { if (value !== undefined && value !== '') params.set(key, String(value)) })
  return params.toString()
}
function post<T>(path: string, payload: object, requestKey: string) {
  return customerRequest<T>(`${base}${path}`, { method: 'POST', body: JSON.stringify({ ...payload, requestKey }) })
}
export const fetchAgentClients = (query: Query = {}) => customerRequest<AgentPage<AgentClient>>(`${base}/clients?${queryString(query)}`)
export const fetchAgentClient = (id: number) => customerRequest<AgentClientDetail>(`${base}/clients/${id}`)
export const createAgentClient = (payload: AgentClientInput, key: string) => post<AgentClient>('/clients', payload, key)
export const updateAgentClient = (id: number, payload: AgentClientInput) => customerRequest<AgentClient>(`${base}/clients/${id}`, { method: 'PUT', body: JSON.stringify(payload) })
export const fetchAgentIntakes = (query: Query = {}) => customerRequest<AgentPage<AgentIntake>>(`${base}/intakes?${queryString(query)}`)
export const fetchAgentIntake = (id: number) => customerRequest<AgentIntakeDetail>(`${base}/intakes/${id}`)
export const createAgentIntake = (payload: AgentIntakeInput, key: string) => post<AgentIntakeDetail>('/intakes', payload, key)
export const receiveAgentIntake = (id: number, payload: { note: string }, key: string) => post<AgentIntakeDetail>(`/intakes/${id}/receive`, payload, key)
export const checkInAgentCard = (id: number, payload: { inventoryCode: string; conditionNote: string; hasException: boolean }, key: string) => post<AgentIntakeDetail>(`/intakes/${id}/check-in`, payload, key)
export const fetchAgentCards = (query: Query = {}) => customerRequest<AgentPage<AgentCard>>(`${base}/cards?${queryString(query)}`)
export const fetchAgentEvents = (query: Query = {}) => customerRequest<AgentPage<AgentEvent>>(`${base}/events?${queryString(query)}`)
export const createAgentSubmission = (payload: AgentSubmissionInput, key: string) => post<AgentSubmission>('/submissions', payload, key)
export const checkAgentReturn = (id: number, payload: { inventoryCode: string; note: string }, key: string) => post<AgentCard>(`/cards/${id}/return-check`, payload, key)
export const fetchAgentShipments = (query: Query = {}) => customerRequest<AgentPage<AgentShipment>>(`${base}/return-shipments?${queryString(query)}`)
export const fetchAgentShipment = (id: number) => customerRequest<AgentShipmentDetail>(`${base}/return-shipments/${id}`)
export const createAgentShipment = (payload: AgentShipmentInput, key: string) => post<AgentShipmentDetail>('/return-shipments', payload, key)
export const deliverAgentShipment = (id: number, payload: { note: string }, key: string) => post<AgentShipmentDetail>(`/return-shipments/${id}/delivered`, payload, key)
export const uploadAgentCardPhoto = (id: number, side: 'front' | 'back', file: File) => {
  const body = new FormData(); body.set('file', file)
  return customerRequest<AgentCard>(`${base}/cards/${id}/photos?side=${side}`, { method: 'POST', body })
}

// A failed operation keeps its key only while its content remains identical.
export const agentWorkbenchBusy = ref(false)
export function useAgentActions() {
  const busy = agentWorkbenchBusy, error = ref(''), success = ref('')
  const attempts = new Map<string, { fingerprint: string; key: string }>()
  async function run<T>(name: string, payload: object, request: (key: string) => Promise<T>, apply: (value: T) => void, message: string) {
    if (busy.value) return false
    const fingerprint = JSON.stringify(payload)
    let attempt = attempts.get(name)
    if (!attempt || attempt.fingerprint !== fingerprint) {
      attempt = { fingerprint, key: crypto.randomUUID() }
      attempts.set(name, attempt)
    }
    busy.value = true; error.value = ''; success.value = ''
    try {
      const value = await request(attempt.key)
      attempts.delete(name)
      apply(value)
      success.value = message
      return true
    } catch (e) {
      error.value = e instanceof Error ? e.message : '操作未完成，请重试。'
      return false
    } finally { busy.value = false }
  }
  return { busy, error, success, run }
}

const statusLabels: Record<string, string> = {
  expected: '待签收', received: '待清点', ready: '已齐全入库', exception: '有异常', in_stock: '已入库',
  submitted: '已送评', returned: '回卡已核对', return_shipped: '已回寄客户', shipped: '已发件', delivered: '已签收',
  draft: '待处理', awaiting_inbound: '待寄往 NXR', inbound_shipped: '寄往 NXR 中', grading: '评级中',
  outbound_shipped: 'NXR 已寄回', completed: '评级完成', cancelled: '已取消',
}
export const agentStatusLabel = (code: string) => statusLabels[code] || code
const eventLabels: Record<string, string> = {
  client_created: '建立客户档案', client_updated: '更新客户档案', client_archived: '归档客户', client_reactivated: '恢复客户',
  intake_created: '登记来件', intake_received: '签收来件', card_checked_in: '卡片清点入库', card_exception: '登记卡片异常',
  card_photo_uploaded: '上传卡片照片', submission_created: '生成送评批次', card_return_checked: '回卡核对',
  return_shipment_created: '回寄客户', return_shipment_delivered: '客户签收',
  photo_added: '上传卡片照片', intake_submitted: '来件已送评', card_return_shipped: '卡片已回寄客户',
  shipment_created: '登记客户回寄', client_received_card: '客户已收到卡片', shipment_delivered: '客户签收回寄',
}
export const agentEventLabel = (code: string) => eventLabels[code] || code
export const agentDateLabel = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN') : '—'
export const agentAddressLabel = (address: AgentAddress) => [address.country, address.region, address.city, address.addressLine1, address.addressLine2, address.postalCode].filter(Boolean).join(' · ')
