import { customerRequest, type GradingOrder } from './customer'

export type MerchantProfile = { customerId: number; companyName: string; contactName: string; updatedAt: string | null }
export type Wallet = { currencyCode: string; balance: number; updatedAt: string | null }
export type WalletTransaction = { id: number; transactionTypeCode: string; directionCode: string; amount: number; balanceAfter: number; currencyCode: string; referenceNo?: string; note?: string; createdAt: string }
export type Recharge = { id: number; rechargeNo: string; currencyCode: string; amount: number; statusCode: string; payerReference: string; proofReference: string; reviewNote?: string; createdAt: string }
export type Page<T> = { items: T[]; page: number; pageSize: number; total: number }

export const fetchMerchantProfile = () => customerRequest<MerchantProfile>('/api/customer/merchant/profile')
export const saveMerchantProfile = (profile: Pick<MerchantProfile, 'companyName' | 'contactName'>) => customerRequest<MerchantProfile>('/api/customer/merchant/profile', { method: 'PUT', body: JSON.stringify(profile) })
export const fetchWallets = () => customerRequest<Wallet[]>('/api/customer/merchant/wallets')
export const fetchWalletTransactions = (currency: string, page = 1) => customerRequest<Page<WalletTransaction>>(`/api/customer/merchant/wallets/${encodeURIComponent(currency)}/transactions?page=${page}&pageSize=20`)
export const fetchRecharges = (page = 1) => customerRequest<Page<Recharge>>(`/api/customer/merchant/wallet-recharges?page=${page}&pageSize=20`)
export const requestRecharge = (payload: { currencyCode: string; amount: string; providerCode: string; payerReference: string; proofReference: string }) => customerRequest<Recharge>('/api/customer/merchant/wallet-recharges', { method: 'POST', body: JSON.stringify(payload) })
export const payFromWallet = (orderNo: string, idempotencyKey: string) => customerRequest<GradingOrder>(`/api/customer/orders/${encodeURIComponent(orderNo)}/wallet-payment`, { method: 'POST', body: JSON.stringify({ idempotencyKey }) })
export const cancelOrder = (orderNo: string, reason: string) => customerRequest<GradingOrder>(`/api/customer/orders/${encodeURIComponent(orderNo)}/cancel`, { method: 'POST', body: JSON.stringify({ reason }) })
