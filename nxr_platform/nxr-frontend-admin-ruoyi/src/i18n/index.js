import { createI18n } from 'vue-i18n'
import en from './messages/en'
import zhCN from './messages/zh-CN'
import baseLiteralZhCN from './literal-zh-CN'
import workspaceZhCN from './partner-workspace-zh-CN'
import partnerZhCN from './partner-management-zh-CN'
import overviewZhCN from './workspace-overview-zh-CN'
import enterpriseWalletZhCN from './enterprise-wallet-zh-CN'
import cardOperatorsZhCN from './card-operators-zh-CN'
import partnerReturnsZhCN from './partner-returns-zh-CN'
import { localizeKnownOrderText, localizeDefaultOrderText, localizeOrderEventDetail as translateOrderEventDetail, orderCodeSource } from './order-system-text'

const literalZhCN = { ...baseLiteralZhCN, ...workspaceZhCN, ...partnerZhCN, ...overviewZhCN, ...enterpriseWalletZhCN, ...cardOperatorsZhCN, ...partnerReturnsZhCN }
export const allowLocaleSelection = ['development', 'java-stage'].includes(import.meta.env.VITE_APP_ENV)

export const supportedLocales = [
  { code: 'en', label: 'English' },
  { code: 'zh-CN', label: '中文' }
]

const STORAGE_KEY = 'nxr-admin-locale'
const LOCAL_DEFAULT_LOCALE = 'zh-CN'
const LOCAL_LOCALE_POLICY_KEY = 'nxr-admin-locale-policy'
const LOCAL_LOCALE_POLICY_VERSION = 'zh-default-20261021'
const BACKEND_MESSAGE_SOURCES = {
  '操作成功': 'Operation completed successfully',
  '操作失败': 'Operation failed',
  '新增成功': 'Added successfully',
  '修改成功': 'Updated successfully',
  '删除成功': 'Deleted successfully',
  '没有权限，请联系管理员授权': 'You do not have permission to perform this action.',
  '演示模式，不允许操作': 'Demo mode does not allow changes',
  '当前系统没有开启注册功能！': 'Registration is not enabled for this system.'
}
const ENGLISH_BY_CHINESE_LITERAL = new Map(
  Object.entries(literalZhCN).map(([english, chinese]) => [chinese, english])
)
const ENGLISH_CARD_CODE_LABELS = new Map([
  ['Inventory code', 'Card code'],
  ['inventory code', 'card code'],
  ['inventory codes', 'card codes'],
  ['Inventory code / card', 'Card code / card'],
  ['Inventory code, certificate ID or card name', 'Card code, certificate ID or card name'],
  ['Scan or enter inventory code / certificate ID', 'Scan or enter card code / certificate ID'],
  ['Scan an inventory code, certificate ID or official NXR certificate QR code.', 'Scan a card code, certificate ID or official NXR certificate QR code.'],
  ['No matching inventory code or certificate ID. Check the card or the current customer filter.', 'No matching card code or certificate ID. Check the card or the current customer filter.'],
  ['Inventory code not recognized.', 'Card code not recognized.'],
  ['Inventory code or certificate number does not match this card', 'Card code or certificate number does not match this card'],
  ['Register and generate inventory codes', 'Register and generate card codes'],
  ['Intake recorded and inventory codes generated.', 'Intake recorded and card codes generated.'],
  ['Once receipt of the NXR return batch is confirmed, scan each inventory code or certificate ID to check the physical cards.', 'Once receipt of the NXR return batch is confirmed, scan each card code or certificate ID to check the physical cards.'],
  ['{p1} labels · QR codes contain inventory codes. Print at actual size.', '{p1} labels · QR codes contain card codes. Print at actual size.'],
  ['Inventory code {p1}', 'Card code {p1}']
])

function resolveInitialLocale() {
  // Hosted builds have no language selector, so stale local choices must not change their language.
  if (!allowLocaleSelection) return 'en'
  if (typeof window === 'undefined') return LOCAL_DEFAULT_LOCALE
  const savedLocale = window.localStorage.getItem(STORAGE_KEY)
  const savedPolicy = window.localStorage.getItem(LOCAL_LOCALE_POLICY_KEY)
  // Migrate existing local sessions once so every administrator starts from Chinese.
  // Later explicit language choices remain respected under the current policy version.
  if (savedPolicy !== LOCAL_LOCALE_POLICY_VERSION) return LOCAL_DEFAULT_LOCALE
  return supportedLocales.some((item) => item.code === savedLocale) ? savedLocale : LOCAL_DEFAULT_LOCALE
}

export const i18n = createI18n({
  legacy: false,
  globalInjection: true,
  locale: resolveInitialLocale(),
  fallbackLocale: 'en',
  messages: {
    en,
    'zh-CN': zhCN
  }
})

export function persistLocale(locale) {
  if (!supportedLocales.some((item) => item.code === locale)) return
  window.localStorage.setItem(STORAGE_KEY, locale)
  if (allowLocaleSelection) window.localStorage.setItem(LOCAL_LOCALE_POLICY_KEY, LOCAL_LOCALE_POLICY_VERSION)
  document.documentElement.lang = locale
}

export function activeLocale() {
  return i18n.global.locale.value || 'en'
}

export function tx(source, parameters = {}) {
  const locale = activeLocale()
  const englishSource = ENGLISH_BY_CHINESE_LITERAL.get(source) || localizeKnownOrderText(source, 'en')
  const translated = locale === 'zh-CN'
    ? literalZhCN[source] || localizeKnownOrderText(source, locale)
    : ENGLISH_CARD_CODE_LABELS.get(englishSource) || englishSource

  return Object.entries(parameters).reduce(
    (message, [key, value]) => message.replaceAll(`{${key}}`, String(value)),
    translated
  )
}

export const localizeOrderTerms = value => localizeDefaultOrderText(value, activeLocale())
export const localizeOrderEventDetail = event => translateOrderEventDetail(event, activeLocale())
export function localizeOrderCode(domain, value) {
  const source = orderCodeSource(domain, value)
  return source === value ? value : tx(source)
}

export function localizeBackendMessage(message) {
  if (message === null || message === undefined) return message
  const source = String(message)
  const exact = BACKEND_MESSAGE_SOURCES[source] || ENGLISH_BY_CHINESE_LITERAL.get(source)
  if (exact) return tx(exact)

  const missingPathVariable = source.match(/^请求路径中缺少必需的路径变量\[(.+)]$/)
  if (missingPathVariable) return activeLocale() === 'zh-CN' ? source : `A required path variable is missing: ${missingPathVariable[1]}`

  const typeMismatch = source.match(/^请求参数类型不匹配，参数\[(.+?)]要求类型为：'(.+?)'，但输入值为：'(.+)'$/)
  if (typeMismatch) {
    return activeLocale() === 'zh-CN' ? source : `Invalid value "${typeMismatch[3]}" for ${typeMismatch[1]}; expected ${typeMismatch[2]}.`
  }

  const known = tx(source)
  if (known !== source) return known
  // Partner return errors may prefix a known reason with the immutable order number.
  const orderReason = source.match(/^((?:NXR|ORD)-[A-Za-z0-9-]+: )(.+)$/)
  if (orderReason) return orderReason[1] + tx(orderReason[2])
  return source
}

persistLocale(i18n.global.locale.value)
