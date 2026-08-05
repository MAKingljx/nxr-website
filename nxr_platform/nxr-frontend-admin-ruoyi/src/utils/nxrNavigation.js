const HIDDEN_SCAFFOLD_ROOTS = new Set(['system', 'monitor', 'tool'])

function cleanPath(path = '') {
  return String(path).replace(/^\/+|\/+$/g, '')
}

function normalizeRoute(route, depth) {
  const path = cleanPath(route.path)
  if (depth === 0 && HIDDEN_SCAFFOLD_ROOTS.has(path)) {
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
      .map((child) => normalizeRoute(child, depth + 1))
      .filter(Boolean)
  }

  return normalized
}

export function prepareNxrBusinessRoutes(routes = []) {
  return routes.map((route) => normalizeRoute(route, 0)).filter(Boolean)
}

export function isNxrBusinessPath(path = '') {
  const root = cleanPath(path).split('/')[0]
  return !HIDDEN_SCAFFOLD_ROOTS.has(root)
}
