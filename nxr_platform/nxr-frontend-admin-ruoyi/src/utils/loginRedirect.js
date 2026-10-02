export function safeLoginTarget(target) {
  return typeof target === 'string' && target.startsWith('/') && !target.startsWith('//')
    && !target.startsWith('/login') && !target.includes('\\') ? target : '/index'
}

export function loginLocation(target) {
  return { path: '/login', query: { redirect: safeLoginTarget(target) }, replace: true }
}

export function loginDestination(router, target, query = {}) {
  const resolved = router.resolve(safeLoginTarget(target))
  return { path: resolved.path, query: { ...resolved.query, ...query }, hash: resolved.hash }
}

export function browserReloginUrl(location, baseUrl = '/') {
  const base = baseUrl.endsWith('/') ? baseUrl : baseUrl + '/'
  const prefix = base.slice(0, -1)
  let path = location.pathname || '/index'
  if (prefix && path.startsWith(base)) path = path.slice(prefix.length)
  const target = safeLoginTarget(path + (location.search || '') + (location.hash || ''))
  return base + 'login?redirect=' + encodeURIComponent(target)
}
