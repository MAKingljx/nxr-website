import { i18n } from '@/i18n'

const HIDDEN_TOOL_COMPONENTS = new Set(['tool/build/index', 'tool/gen/index'])

const MENU_TITLE_KEYS = new Map([
  ['NXR后台', 'nav.nxrAdmin'],
  ['NXR管理', 'nav.nxrAdmin'],
  ['卡牌管理', 'nav.cardManagement'],
  ['新建卡牌', 'nav.newCard'],
  ['新建录入', 'nav.newEntry'],
  ['卡牌列表', 'nav.cardEntries'],
  ['录入管理', 'nav.cardEntries'],
  ['全部录入', 'nav.allEntries'],
  ['待审核', 'nav.pendingReview'],
  ['已批准', 'nav.approvedEntries'],
  ['卡图上传', 'nav.cardImageUpload'],
  ['上传管理', 'nav.cardImageUpload'],
  ['上传与发布', 'nav.uploadPublish'],
  ['送审管理', 'nav.submissionManagement'],
  ['订单管理', 'nav.orderManagement'],
  ['送评订单', 'nav.gradingOrders'],
  ['客户管理', 'nav.customerManagement'],
  ['候补名单', 'nav.waitlist'],
  ['提交者名单', 'nav.waitlist'],
  ['数据导出', 'nav.dataExport'],
  ['Excel 导出', 'nav.excelExport'],
  ['Excel导出', 'nav.excelExport'],
  ['系统设置', 'nav.systemSettings'],
  ['系统管理', 'nav.systemManagement'],
  ['管理员用户', 'nav.adminUsers'],
  ['用户管理', 'nav.adminUsers'],
  ['角色管理', 'nav.roleManagement'],
  ['菜单管理', 'nav.menuManagement'],
  ['部门管理', 'nav.departmentManagement'],
  ['岗位管理', 'nav.positionManagement'],
  ['字典设置', 'nav.dictionarySettings'],
  ['字典管理', 'nav.dictionarySettings'],
  ['参数设置', 'nav.configuration'],
  ['通知公告', 'nav.notices'],
  ['日志管理', 'nav.logManagement'],
  ['操作日志', 'nav.operationLogs'],
  ['登录日志', 'nav.loginLogs'],
  ['系统监控', 'nav.systemMonitoring'],
  ['在线用户', 'nav.onlineUsers'],
  ['定时任务', 'nav.scheduledJobs'],
  ['数据监控', 'nav.dataMonitoring'],
  ['服务监控', 'nav.serverMonitoring'],
  ['缓存监控', 'nav.cacheMonitoring'],
  ['缓存列表', 'nav.cacheList'],
  ['系统工具', 'nav.systemTools'],
  ['系统接口', 'nav.apiDocumentation'],
  ['品牌设置', 'nav.brandSettings'],
  ['主要功能', 'nav.mainOperations'],
  ['工具', 'nav.tools'],
  ['客户运营', 'nav.customerOperations']
])

// Keep both the former section URLs and the older flat URLs reachable while
// the canonical navigation moves to task-oriented business entries.
const LEGACY_ROUTE_ALIASES = new Map([
  ['nxr/entries/index|new-entry', ['/nxr/main/new-entry']],
  ['nxr/entries/index|entries', ['/nxr/entries', '/nxr/main/entries']],
  ['nxr/entries/index|pending-review', ['/nxr/main/pending-review']],
  ['nxr/entries/index|approved-entries', ['/nxr/main/approved-entries']],
  ['nxr/upload/index|upload', ['/nxr/tools/upload']],
  ['nxr/exports/index|exports', ['/nxr/tools/exports']],
  ['nxr/waitlist/index|waitlist', ['/nxr/tools/waitlist']],
  ['nxr/orders/index|orders', ['/nxr/orders', '/nxr/customer-ops/orders']],
  ['nxr/customers/index|customers', ['/nxr/customer-ops/customers']],
  ['nxr/brands/index|brands', ['/nxr/brands']],
  ['system/user/index|user', ['/system/user']],
  ['system/dict/index|dict', ['/system/dict']]
])

function cleanPath(path = '') {
  return String(path).replace(/^\/+|\/+$/g, '')
}

function normalizeRoute(route) {
  const component = cleanPath(route.component)

  if (HIDDEN_TOOL_COMPONENTS.has(component)) {
    return null
  }

  const normalized = {
    ...route,
    meta: route.meta ? { ...route.meta } : route.meta
  }

  if (normalized.meta?.title) {
    const titleKey = MENU_TITLE_KEYS.get(normalized.meta.title)
    normalized.meta.title = titleKey ? i18n.global.t(titleKey) : normalized.meta.title
  }

  if (normalized.component === 'nxr/customers/index' && normalized.meta) {
    normalized.meta.title = i18n.global.t('nav.customerManagement')
    normalized.meta.icon = 'peoples'
  }

  const legacyAliases = LEGACY_ROUTE_ALIASES.get(`${component}|${cleanPath(route.path)}`)
  if (legacyAliases) {
    normalized.alias = legacyAliases
  }

  if (Array.isArray(route.children)) {
    normalized.children = route.children
      .map((child) => normalizeRoute(child))
      .filter(Boolean)
  }

  return normalized
}

export function prepareNxrBusinessRoutes(routes = []) {
  return routes.map((route) => normalizeRoute(route)).filter(Boolean)
}
