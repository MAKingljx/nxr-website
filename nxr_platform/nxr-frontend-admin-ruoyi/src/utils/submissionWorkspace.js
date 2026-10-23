export const WORKSPACE_ROOT = '/nxr/submission-workbench'
export const WORKSPACE_VIEWS = Object.freeze(['orders', 'batches', 'clients', 'cards', 'intakes', 'returns', 'wallet', 'addresses'])

const WORKSPACE_COMPONENT = 'nxr/agent-workbench/index'
const PARTNERS_COMPONENT = 'nxr/partners/index'
const LEGACY_WORKSPACE_PATHS = new Set([WORKSPACE_ROOT, '/nxr/agent-workbench'])
const PRIMARY_VIEWS = new Set(['orders', 'batches', 'addresses'])
const VIEW_ICONS = { orders: 'clipboard', clients: 'user', cards: 'post', intakes: 'clipboard', batches: 'list', returns: 'tree-table', wallet: 'money', addresses: 'guide' }

const LEGACY_VIEWS = { clients: 'orders', cards: 'orders', intakes: 'orders', returns: 'batches' }

function validView(view) {
  return typeof view === 'string' && WORKSPACE_VIEWS.includes(view)
}

export function workspacePath(view = 'orders') {
  return `${WORKSPACE_ROOT}/${view === 'partners' || validView(view) ? view : 'orders'}`
}

export function resolveWorkspaceView(route = {}) {
  if (validView(route.meta?.workspaceView)) return LEGACY_VIEWS[route.meta.workspaceView] || route.meta.workspaceView
  if (validView(route.query?.tab)) return LEGACY_VIEWS[route.query.tab] || route.query.tab
  return 'orders'
}

// RuoYi filters these source routes on the server. Regroup only the returned
// capabilities, so a bound partner never receives the management page.
export function groupSubmissionWorkspaceRoutes(routes = [], translate = key => key) {
  let workspaceSource
  let partnersSource
  let insertAt = -1
  const remaining = []

  function extract(route, inheritedHidden = false) {
    const component = String(route.component || '').replace(/^\/+|\/+$/g, '')
    const hidden = inheritedHidden || Boolean(route.hidden)
    if (component === WORKSPACE_COMPONENT || component === PARTNERS_COMPONENT) {
      const source = { ...route, hidden }
      if (component === WORKSPACE_COMPONENT) workspaceSource ||= source
      else partnersSource ||= source
      return null
    }
    if (!Array.isArray(route.children)) return route
    const children = route.children.map(child => extract(child, hidden)).filter(Boolean)
    if (route.children.length && !children.length) return null
    return { ...route, children }
  }

  for (const route of routes) {
    const before = Boolean(workspaceSource || partnersSource)
    const remainingRoute = extract(route)
    if (!before && (workspaceSource || partnersSource)) insertAt = remaining.length
    if (remainingRoute) remaining.push(remainingRoute)
  }
  if (!workspaceSource && !partnersSource) return remaining

  function child(source, view, component, titleKey, icon) {
    const result = {
      ...source,
      path: view,
      name: `NxrWorkspace${view[0].toUpperCase()}${view.slice(1)}`,
      component,
      meta: { ...source.meta, title: translate(titleKey), icon, activeMenu: workspacePath(view) }
    }
    // Original aliases and tab queries belong to one entry, not all six views.
    delete result.alias
    delete result.query
    delete result.children
    delete result.redirect
    return result
  }

  const children = []
  if (partnersSource) {
    const partners = child(partnersSource, 'partners', PARTNERS_COMPONENT, 'nav.partnerManagement', 'peoples')
    partners.alias = ['/nxr/partners']
    children.push(partners)
  }
  if (workspaceSource) {
    for (const view of WORKSPACE_VIEWS) {
      const entry = child(workspaceSource, view, WORKSPACE_COMPONENT, `nav.workspace${view[0].toUpperCase()}${view.slice(1)}`, VIEW_ICONS[view])
      entry.meta.workspaceView = view
      entry.hidden = Boolean(entry.hidden) || !PRIMARY_VIEWS.has(view)
      if (!PRIMARY_VIEWS.has(view)) entry.meta.activeMenu = workspacePath(view === 'returns' ? 'batches' : 'orders')
      if (view === 'orders') entry.alias = ['/nxr/agent-workbench']
      if (view === 'wallet') entry.permissions = ['nxr:customer:finance']
      children.push(entry)
    }
  }
  remaining.splice(insertAt, 0, {
    path: WORKSPACE_ROOT,
    name: 'NxrSubmissionWorkspace',
    component: 'Layout',
    alwaysShow: true,
    hidden: children.every(entry => entry.hidden),
    redirect: workspacePath(workspaceSource ? 'orders' : 'partners'),
    meta: { title: translate('nav.submissionWorkspace'), icon: 'peoples' },
    children
  })
  return remaining
}

export function legacyWorkspaceLocation(route = {}) {
  const original = route.redirectedFrom || route
  const directView = original.path?.startsWith(`${WORKSPACE_ROOT}/`) ? original.path.slice(WORKSPACE_ROOT.length + 1) : ''
  const legacyView = Object.hasOwn(LEGACY_VIEWS, directView) ? directView : ''
  if (!legacyView && !LEGACY_WORKSPACE_PATHS.has(original.path)) return null
  if (!legacyView && route.path?.startsWith(`${WORKSPACE_ROOT}/`) && !Object.hasOwn(route.query || {}, 'tab')) return null
  const query = { ...route.query }
  const view = legacyView || (validView(query.tab) ? query.tab : 'orders')
  delete query.tab
  if (['clients', 'cards', 'returns'].includes(view)) { delete query.record; delete query.clientId }
  const path = workspacePath(LEGACY_VIEWS[view] || view)
  if (route.path === path && !Object.hasOwn(route.query || {}, 'tab')) return null
  return { path, query, hash: route.hash, replace: true }
}

export function workspaceSidebarTarget(path, route = {}, query = {}) {
  const targetInWorkspace = path.startsWith(`${WORKSPACE_ROOT}/`)
  const sourceInWorkspace = route.path?.startsWith(`${WORKSPACE_ROOT}/`) || LEGACY_WORKSPACE_PATHS.has(route.path) || route.path === '/nxr/partners'
  const company = route.query?.company
  const mergedQuery = { ...query }
  if (targetInWorkspace && sourceInWorkspace && typeof company === 'string' && /^[1-9]\d*$/.test(company)) {
    mergedQuery.company = company
  }
  return Object.keys(mergedQuery).length ? { path, query: mergedQuery } : path
}

// A separate fulfillment entry reuses only the authorized order-management
// capability returned by the server, never the partner or card-entry capability.
export function addPartnerFulfillmentRoute(routes = [], translate = key => key) {
  let source
  function inspect(entries, hidden = false) {
    for (const route of entries) {
      const inheritedHidden = hidden || Boolean(route.hidden)
      if (String(route.component || '').replace(/^\/+|\/+$/g, '') === 'nxr/partner-fulfillment/index') return true
      if (String(route.component || '').replace(/^\/+|\/+$/g, '') === 'nxr/orders/index') source ||= { ...route, hidden: inheritedHidden }
      if (route.children && inspect(route.children, inheritedHidden)) return true
    }
    return false
  }
  if (inspect(routes) || !source) return routes
  const permissions = ['nxr:order:manage', 'nxr:order:workbench', 'nxr:order:shipping', 'nxr:order:batch']
  return [...routes, {
    path: '/nxr/submissions', component: 'Layout', hidden: source.hidden, permissions,
    children: [{
      path: 'partner-fulfillment', name: 'NxrPartnerFulfillment',
      component: 'nxr/partner-fulfillment/index', hidden: source.hidden, permissions,
      meta: { title: translate('nav.partnerFulfillment'), icon: 'list', activeMenu: '/nxr/submissions/partner-fulfillment' }
    }]
  }]
}
