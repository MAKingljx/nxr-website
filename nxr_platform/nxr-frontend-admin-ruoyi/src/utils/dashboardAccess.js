export function canViewDashboard(permissions = []) {
  return permissions.includes('*:*:*') || permissions.includes('nxr:dashboard:view')
}

export function firstAccessibleBusinessPath(routes = []) {
  const pages = []

  function visit(items, parentPath = '') {
    for (const route of items) {
      if (route.hidden || route.meta?.link) continue
      const path = route.path?.startsWith('/')
        ? route.path
        : `${parentPath}/${route.path || ''}`.replace(/\/+/g, '/')
      if (route.children?.length) {
        visit(route.children, path)
      } else if (route.name) {
        pages.push({ path, name: route.name })
      }
    }
  }

  visit(routes)
  return pages.find((page) => page.name === 'NxrEntries')?.path
    || pages.find((page) => page.path.startsWith('/nxr/'))?.path
    || pages[0]?.path
    || null
}
