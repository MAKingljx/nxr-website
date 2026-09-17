import request from '@/utils/request'

export const CREDIT_CURRENCIES = ['CNY', 'USD', 'EUR', 'GBP', 'HKD', 'JPY', 'CAD', 'AUD', 'SGD'] as const
export type CreditCurrency = typeof CREDIT_CURRENCIES[number]
export type CreditDecimal = number | string
export type CreditRate = { currencyCode: string; cnyPerUnit: CreditDecimal | null; enabled: boolean }
export type EnterpriseCreditSettings = {
  version: number
  pointsPerCny: CreditDecimal
  pointScale: 2
  rates: CreditRate[]
  updatedAt: string | null
}
export type EnterpriseCreditSettingsInput = {
  expectedVersion: number
  pointsPerCny: CreditDecimal
  rates: CreditRate[]
}
export type CreditQuote = {
  settingsVersion: number
  sourceCurrency: string
  sourceAmount: CreditDecimal
  cnyPerUnit: CreditDecimal
  pointsPerCny: CreditDecimal
  points: CreditDecimal
  unitCode: 'PTS'
}
export type LegacyCreditBalance = { currencyCode: string; balance: CreditDecimal }
export type EnterpriseCreditSummary = {
  unitCode: 'PTS'
  balance: CreditDecimal
  settingsVersion: number
  pointsPerCny: CreditDecimal
  rates: CreditRate[]
  legacyBalances: LegacyCreditBalance[]
}
export type CreditConversionPreview = {
  settingsVersion: number
  balances: Array<LegacyCreditBalance & { points: CreditDecimal }>
  totalPoints: CreditDecimal
}
export type CreditConversionInput = {
  expectedVersion: number
  balances: LegacyCreditBalance[]
  idempotencyKey: string
  note: string
}
export type CreditConversionResult = {
  id: number
  customerId: number
  settingsVersion: number
  points: CreditDecimal
  createdAt: string
}

// Preserve the server error status so forms can keep edits after a settings conflict.
async function creditRequest<T>(path: string, method = 'get', data?: object): Promise<T> {
  const response = await request({
    url: `/api/admin/enterprise-credit${path}`,
    method,
    data,
    headers: { repeatSubmit: false },
    suppressErrorMessage: true
  }) as unknown as { data: T }
  return response.data
}

export const getEnterpriseCreditSettings = () => creditRequest<EnterpriseCreditSettings>('/settings')
export const updateEnterpriseCreditSettings = (data: EnterpriseCreditSettingsInput) =>
  creditRequest<EnterpriseCreditSettings>('/settings', 'put', data)
export const getEnterpriseCreditSummary = (companyId: number) =>
  creditRequest<EnterpriseCreditSummary>(`/companies/${companyId}`)
export const quoteCompanyCredit = (companyId: number, data: { currencyCode: string; amount: CreditDecimal }) =>
  creditRequest<CreditQuote>(`/companies/${companyId}/quote`, 'post', data)
export const previewEnterpriseCreditConversion = (companyId: number) =>
  creditRequest<CreditConversionPreview>(`/companies/${companyId}/conversion-preview`, 'post')
export const convertEnterpriseCreditBalances = (companyId: number, data: CreditConversionInput) =>
  creditRequest<CreditConversionResult>(`/companies/${companyId}/convert`, 'post', data)
export const quoteEnterpriseCreditRecharge = (companyId: number, rechargeId: number, settingsVersion: number) =>
  creditRequest<CreditQuote>(`/companies/${companyId}/recharges/${rechargeId}/quote`, 'post', { settingsVersion })

export function getCreditApiError(error: any): { status: number | undefined; message: string } {
  return {
    status: error?.response?.data?.code || error?.status || error?.response?.status,
    message: String(error?.response?.data?.message || error?.response?.data?.msg || error?.message || '')
  }
}
