import { customerRequest, customerSession } from './customer'
export type OrderQuote = {
  customerId: number; destinationCountry: string; currencyCode: string; cardCount: number;
  unitPrice: number; serviceFee: number; returnShippingFee: number; totalAmount: number;
  totalWeightGrams: number | null; chargeableWeightGrams: number | null; shippingOptionCode: string;
  shippingSourceCode: string; shippingDisplayName: string;
}
export type BatchQuote = { aggregate: OrderQuote; allocations: Array<{ reference: string; cardCount: number; serviceFee: number; returnShippingFee: number; totalAmount: number }> }
export const fetchBatchQuote = (country: string, currency: string, shippingOptionCode: string, parts: Array<{ reference: string; cardCount: number }>) =>
  customerRequest<BatchQuote>('/api/customer/commerce/batch-quote-preview', { method: 'POST', body: JSON.stringify({ customerId: customerSession.value?.customer.id, country, currency, shippingOptionCode, parts }) })
export const fetchOrderQuote = (country: string, currency: string, count: number, shippingOptionCode: string) => {
  const customerId = customerSession.value?.customer.id
  if (!customerId) throw new Error('Please sign in before requesting a quote.')
  const query = new URLSearchParams({ customerId: String(customerId), country, currency, count: String(count), shippingOptionCode })
  return customerRequest<OrderQuote>(`/api/customer/commerce/quote-preview?${query}`)
}
