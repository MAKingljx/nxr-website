import { customerRequest } from './customer'
export type PaymentOption = { provider: string; displayName: string; mode: string; currencies: string[]; flow: string; manualProof: boolean }
export type Checkout = { provider: string; paymentUrl: string | null; qrPayload: string | null; status: string; providerOrderId: string | null }
export const fetchPaymentOptions = (currency: string) => customerRequest<PaymentOption[]>(`/api/customer/payment-options?currency=${encodeURIComponent(currency)}`, {}, false)
export const createCheckout = (orderNo: string, provider: string, idempotencyKey: string) => customerRequest<Checkout>(`/api/customer/orders/${encodeURIComponent(orderNo)}/checkout`, { method: 'POST', body: JSON.stringify({ provider, idempotencyKey }) })
export const capturePaypal = (orderNo: string, providerOrderId: string) => customerRequest<Checkout>(`/api/customer/orders/${encodeURIComponent(orderNo)}/checkout/paypal/${encodeURIComponent(providerOrderId)}/capture`, { method: 'POST' })
