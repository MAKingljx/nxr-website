import { customerRequest, type GradingOrder } from './customer'

export function sameCreditAmount(left: string | number, right: string | number) {
  const canonical = (value: string | number) => {
    const match = String(value).trim().match(/^\+?(\d*)(?:\.(\d*))?$/)
    if (!match || (!match[1] && !match[2])) return null
    const whole = (match[1] || '0').replace(/^0+(?=\d)/, ''), fraction = (match[2] || '').replace(/0+$/, '')
    return `${whole}${fraction ? `.${fraction}` : ''}`
  }
  const amount = canonical(left)
  return amount !== null && amount === canonical(right)
}


export type CreditQuote = { settingsVersion: number; sourceCurrency: string; sourceAmount: number | string; cnyPerUnit: number | string; pointsPerCny: number | string; points: number | string; unitCode: 'PTS' }
export type EnterpriseCreditSummary = { unitCode: 'PTS'; balance: number | string; settingsVersion: number; pointsPerCny: number | string; rates: Array<{ currencyCode: string; cnyPerUnit: number | string | null; enabled: boolean }>; legacyBalances: Wallet[] }
export type OrderCreditQuote = { quote: CreditQuote; balance: number | string; sufficient: boolean }
export type MerchantProfile = { customerId: number; companyName: string; contactName: string; updatedAt: string | null }
export type Wallet = { currencyCode: string; balance: number | string; updatedAt: string | null }
export type WalletTransaction = { id: number; transactionTypeCode: string; directionCode: string; amount: number | string; balanceAfter: number | string; currencyCode: string; referenceNo?: string; sourceCurrency?: string | null; sourceAmount?: number | string | null; points?: number | string | null; settingsVersion?: number | null; note?: string; createdAt: string }
export type Recharge = { id: number; rechargeNo: string; currencyCode: string; amount: number; statusCode: string; payerReference: string; proofReference: string; reviewNote?: string; createdAt: string; creditQuote?: CreditQuote | null }
export type Page<T> = { items: T[]; page: number; pageSize: number; total: number }

export const fetchMerchantProfile = () => customerRequest<MerchantProfile>('/api/customer/merchant/profile')
export const saveMerchantProfile = (profile: Pick<MerchantProfile, 'companyName' | 'contactName'>) => customerRequest<MerchantProfile>('/api/customer/merchant/profile', { method: 'PUT', body: JSON.stringify(profile) })
export const fetchEnterpriseCredit = () => customerRequest<EnterpriseCreditSummary>('/api/customer/merchant/enterprise-credit')
export const fetchCreditQuote = (payload: { currencyCode: string; amount: string | number }) => customerRequest<CreditQuote>('/api/customer/merchant/credit-quote', { method: 'POST', body: JSON.stringify(payload) })
export const fetchOrderCreditQuote = (orderNo: string) => customerRequest<OrderCreditQuote>(`/api/customer/orders/${encodeURIComponent(orderNo)}/credit-quote`)
export const fetchWallets = () => customerRequest<Wallet[]>('/api/customer/merchant/wallets')
export const fetchWalletTransactions = (currency: string, page = 1) => customerRequest<Page<WalletTransaction>>(`/api/customer/merchant/wallets/${encodeURIComponent(currency)}/transactions?page=${page}&pageSize=20`)
export const fetchRecharges = (page = 1) => customerRequest<Page<Recharge>>(`/api/customer/merchant/wallet-recharges?page=${page}&pageSize=20`)
export const requestRecharge = (payload: { currencyCode: string; amount: string; providerCode: string; payerReference: string; proofReference: string; settingsVersion: number }) => customerRequest<Recharge>('/api/customer/merchant/wallet-recharges', { method: 'POST', body: JSON.stringify(payload) })
export const payFromWallet = (orderNo: string, idempotencyKey: string, settingsVersion: number, expectedPoints: string) => customerRequest<GradingOrder>(`/api/customer/orders/${encodeURIComponent(orderNo)}/wallet-payment`, { method: 'POST', body: JSON.stringify({ idempotencyKey, settingsVersion, expectedPoints }) })
export const cancelOrder = (orderNo: string, reason: string) => customerRequest<GradingOrder>(`/api/customer/orders/${encodeURIComponent(orderNo)}/cancel`, { method: 'POST', body: JSON.stringify({ reason }) })
