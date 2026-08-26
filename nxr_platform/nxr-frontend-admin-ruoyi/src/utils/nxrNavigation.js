const HIDDEN_TOOL_COMPONENTS = new Set(['tool/build/index', 'tool/gen/index'])

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

  if (normalized.component === 'nxr/customers/index' && normalized.meta) {
    normalized.meta.title = '客户管理'
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
