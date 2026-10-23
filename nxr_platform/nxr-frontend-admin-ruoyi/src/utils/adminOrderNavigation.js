import { WORKSPACE_ROOT, workspacePath } from './submissionWorkspace.js'

export const ADMIN_ORDER_ROOT = '/nxr/submissions'
export const ADMIN_BATCHES_PATH = `${ADMIN_ORDER_ROOT}/batches`
export const ADMIN_ORDERS_PATH = `${ADMIN_ORDER_ROOT}/orders`
export const ADMIN_RETURNS_PATH = `${ADMIN_ORDER_ROOT}/partner-fulfillment`

// Regroup only capabilities already returned by the server. Keep original URLs,
// names and permissions so bookmarks and company-scoped operations still work.
export function mergeAdminOrderRoutes(routes, roles = [], translate = key => key) {
  if (!roles.includes('admin')) return routes
  let order, customers, partners, fulfillment, workspaceAvailable = false
  const source = structuredClone(routes)
  const remaining = []
  let insertAt = -1
  function extract(route, parentPath = '', parentHidden = false) {
    const path = route.path.startsWith('/') ? route.path : `${parentPath}/${route.path}`.replace(/\/+/g, '/')
    const hidden = parentHidden || Boolean(route.hidden)
    const entry = { ...route, path, hidden, meta: { ...route.meta } }
    const component = String(route.component || '').replace(/^\/+|\/+$/g, '')
    if (component === 'nxr/orders/index') { order = entry; return null }
    if (component === 'nxr/customers/index') { customers = entry; return null }
    if (component === 'nxr/partners/index') { partners = entry; return null }
    if (component === 'nxr/partner-fulfillment/index') { fulfillment = entry; return null }
    if (component === 'nxr/agent-workbench/index') workspaceAvailable = true
    if (route.children) {
      const children = route.children.map(child => extract(child, path, hidden)).filter(Boolean)
      if (!children.length && route.children.length) return null
      return { ...route, children }
    }
    return route
  }
  for (const route of source) {
    const before = Boolean(order || customers || partners || fulfillment)
    const entry = extract(route)
    if (!before && (order || customers || partners || fulfillment)) insertAt = remaining.length
    if (entry) remaining.push(entry)
  }
  // A management-only role must not gain an order or fulfillment capability.
  if (!order) return routes
  const page = (entry, title, activeMenu = entry.path) => ({
    ...entry,
    meta: { ...entry.meta, title: translate(title), activeMenu, adminUnified: true, adminOrderWorkspaceAvailable: workspaceAvailable }
  })
  const children = [page(order, 'nav.orderManagement')]
  if (fulfillment) {
    const batches = page(fulfillment, 'nav.submitManagement', ADMIN_BATCHES_PATH)
    batches.path = ADMIN_BATCHES_PATH
    batches.name = 'NxrAdminSubmissions'
    batches.meta.adminView = 'batches'
    delete batches.alias
    children.push(batches)
  }
  if (partners) children.push(page(partners, 'nav.adminPartnerManagement'))
  if (customers) children.push({ ...page(customers, 'nav.customerManagement'), hidden: true })
  if (fulfillment) {
    const returns = page(fulfillment, 'nav.fulfillmentManagement')
    returns.meta.adminView = 'returns'
    children.push(returns)
  }

  for (const root of remaining) {
    if (root.path !== WORKSPACE_ROOT) continue
    // Company workspaces remain reachable from record links, but no longer
    // duplicate the administrator's main navigation.
    root.hidden = true
    for (const child of root.children || []) {
      const view = child.meta?.workspaceView
      child.meta = {
        ...child.meta, adminUnified: true, adminOrderWorkspaceAvailable: workspaceAvailable,
        activeMenu: ['batches', 'returns'].includes(view) ? ADMIN_BATCHES_PATH : view === 'addresses' ? partners?.path || order.path : order.path
      }
      if (view === 'orders') child.meta.title = translate('nav.partnerCustomerOrders')
    }
  }
  remaining.splice(insertAt < 0 ? remaining.length : insertAt, 0, {
    path: ADMIN_ORDER_ROOT, name: 'NxrOrderManagement', component: 'Layout', alwaysShow: true,
    hidden: children.every(child => child.hidden), redirect: order.path,
    meta: { title: translate('nav.ordersAndSubmissions'), icon: 'shopping' }, children
  })
  return remaining
}

export function adminOrderLocation(route) {
  if (!route.meta?.adminUnified) return null
  if (route.path === workspacePath('batches') && !route.query?.company && !route.query?.batch && !route.query?.order) {
    return { path: ADMIN_BATCHES_PATH, query: route.query, hash: route.hash, replace: true }
  }
  if ((route.path === ADMIN_ORDERS_PATH && route.query?.batchId) ||
      (route.path === ADMIN_RETURNS_PATH && route.query?.tab === 'submissions')) {
    const query = { ...route.query }
    delete query.tab
    return { path: ADMIN_BATCHES_PATH, query, hash: route.hash, replace: true }
  }
  return null
}
