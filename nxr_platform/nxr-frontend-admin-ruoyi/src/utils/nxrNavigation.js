const HIDDEN_TOOL_COMPONENTS = new Set(['tool/build/index', 'tool/gen/index'])

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
    normalized.meta.title = '用户管理'
    normalized.meta.icon = 'peoples'
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
