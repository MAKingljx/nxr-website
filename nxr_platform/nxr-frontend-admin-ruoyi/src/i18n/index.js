import { createI18n } from 'vue-i18n'
import en from './messages/en'
import zhCN from './messages/zh-CN'
import literalZhCN from './literal-zh-CN'

export const supportedLocales = [
  { code: 'en', label: 'English' },
  { code: 'zh-CN', label: '中文' }
]

const STORAGE_KEY = 'nxr-admin-locale'
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

function resolveInitialLocale() {
  if (typeof window === 'undefined') return 'en'
  const savedLocale = window.localStorage.getItem(STORAGE_KEY)
  return supportedLocales.some((item) => item.code === savedLocale) ? savedLocale : 'en'
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
  document.documentElement.lang = locale
}

export function activeLocale() {
  return i18n.global.locale.value || 'en'
}

export function tx(source, parameters = {}) {
  const locale = activeLocale()
  const translated = locale === 'zh-CN' ? literalZhCN[source] || source : source

  return Object.entries(parameters).reduce(
    (message, [key, value]) => message.replaceAll(`{${key}}`, String(value)),
    translated
  )
}

export function localizeBackendMessage(message) {
  if (message === null || message === undefined) return message
  const source = String(message)
  if (activeLocale() === 'zh-CN') return tx(source)

  const exact = BACKEND_MESSAGE_SOURCES[source] || ENGLISH_BY_CHINESE_LITERAL.get(source)
  if (exact) return exact

  const missingPathVariable = source.match(/^请求路径中缺少必需的路径变量\[(.+)]$/)
  if (missingPathVariable) return `A required path variable is missing: ${missingPathVariable[1]}`

  const typeMismatch = source.match(/^请求参数类型不匹配，参数\[(.+?)]要求类型为：'(.+?)'，但输入值为：'(.+)'$/)
  if (typeMismatch) {
    return `Invalid value "${typeMismatch[3]}" for ${typeMismatch[1]}; expected ${typeMismatch[2]}.`
  }

  return source
}

persistLocale(i18n.global.locale.value)
