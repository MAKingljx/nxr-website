export const WORKSPACE_ROOT = '/nxr/submission-workbench'
export const WORKSPACE_VIEWS = Object.freeze(['clients', 'intakes', 'batches', 'returns', 'wallet', 'addresses'])

const WORKSPACE_COMPONENT = 'nxr/agent-workbench/index'
const PARTNERS_COMPONENT = 'nxr/partners/index'
const LEGACY_WORKSPACE_PATHS = new Set([WORKSPACE_ROOT, '/nxr/agent-workbench'])
const VIEW_ICONS = { clients: 'user', intakes: 'clipboard', batches: 'list', returns: 'tree-table', wallet: 'money', addresses: 'guide' }

function validView(view) {
  return typeof view === 'string' && WORKSPACE_VIEWS.includes(view)
}

export function workspacePath(view = 'clients') {
  return `${WORKSPACE_ROOT}/${view === 'partners' || validView(view) ? view : 'clients'}`
}

export function resolveWorkspaceView(route = {}) {
  if (validView(route.meta?.workspaceView)) return route.meta.workspaceView
  if (validView(route.query?.tab)) return route.query.tab
  return 'clients'
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
      if (view === 'clients') entry.alias = ['/nxr/agent-workbench']
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
    redirect: workspacePath(workspaceSource ? 'clients' : 'partners'),
    meta: { title: translate('nav.submissionWorkspace'), icon: 'peoples' },
    children
  })
  return remaining
}

export function legacyWorkspaceLocation(route = {}) {
  const original = route.redirectedFrom || route
  if (!LEGACY_WORKSPACE_PATHS.has(original.path)) return null
  if (route.path?.startsWith(`${WORKSPACE_ROOT}/`) && !Object.hasOwn(route.query || {}, 'tab')) return null
  const query = { ...route.query }
  const view = validView(query.tab) ? query.tab : 'clients'
  delete query.tab
  const path = workspacePath(view)
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
