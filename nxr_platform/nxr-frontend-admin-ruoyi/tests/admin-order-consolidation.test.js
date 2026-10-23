import assert from 'node:assert/strict'
import test from 'node:test'
import { createRouter, createMemoryHistory } from 'vue-router'
import { mergeAdminOrderRoutes, adminOrderLocation, ADMIN_BATCHES_PATH, ADMIN_ORDERS_PATH, ADMIN_RETURNS_PATH } from '../src/utils/adminOrderNavigation.js'
import { addPartnerFulfillmentRoute, groupSubmissionWorkspaceRoutes, legacyWorkspaceLocation, WORKSPACE_ROOT, workspacePath } from '../src/utils/submissionWorkspace.js'
import en from '../src/i18n/messages/en.js'
import zh from '../src/i18n/messages/zh-CN.js'

const t = messages => key => key.split('.').reduce((value, part) => value[part], messages)
const workspace = { path: '/nxr/agent-workbench', component: 'nxr/agent-workbench/index', permissions: ['nxr:agent:workbench'] }
const partners = { path: '/nxr/partners', component: 'nxr/partners/index', permissions: ['nxr:partner:list'] }
const orders = { path: '/nxr/submissions', component: 'Layout', children: [
  { path: 'orders', name: 'NxrOrders', component: 'nxr/orders/index', alias: ['/nxr/orders'], permissions: ['nxr:order:list'] },
  { path: 'customers', name: 'NxrCustomers', component: 'nxr/customers/index', permissions: ['nxr:customer:list'] }
] }
function prepared() { return addPartnerFulfillmentRoute(groupSubmissionWorkspaceRoutes([orders, partners, workspace], t(zh)), t(zh)) }
const flatten = routes => routes.flatMap(route => [route, ...flatten(route.children || [])])

test('admin has four visible pages while customer management and company workspaces remain reachable', () => {
  const input = prepared(), snapshot = structuredClone(input)
  const routes = mergeAdminOrderRoutes(input, ['admin'], t(zh))
  assert.deepEqual(input, snapshot)
  const visible = routes.filter(route => !route.hidden)
  assert.equal(visible.length, 1)
  assert.equal(visible[0].meta.title, '订单与送评管理')
  assert.deepEqual(visible[0].children.filter(route => !route.hidden).map(route => route.meta.title), ['订单管理','送评管理','代理管理','履约管理'])
  assert.deepEqual(visible[0].children.map(route => route.meta.title), ['订单管理','送评管理','代理管理','客户管理','履约管理'])
  assert.deepEqual(visible[0].children.map(route => route.path), [ADMIN_ORDERS_PATH,ADMIN_BATCHES_PATH,workspacePath('partners'),'/nxr/submissions/customers',ADMIN_RETURNS_PATH])
  const pages = flatten(routes)
  assert.equal(new Set(pages.map(route => route.name).filter(Boolean)).size, pages.filter(route => route.name).length)
  assert.deepEqual(pages.find(route => route.name === 'NxrOrders').permissions, ['nxr:order:list'])
  assert.deepEqual(pages.find(route => route.name === 'NxrWorkspacePartners').permissions, ['nxr:partner:list'])
  const hidden = routes.find(route => route.path === WORKSPACE_ROOT)
  assert.equal(hidden.hidden, true)
  assert.equal(hidden.children.find(route => route.meta.workspaceView === 'orders').meta.activeMenu, ADMIN_ORDERS_PATH)
  assert.equal(hidden.children.find(route => route.meta.workspaceView === 'addresses').meta.activeMenu, workspacePath('partners'))
  assert.equal(mergeAdminOrderRoutes(prepared(), ['admin'], t(en)).find(route => !route.hidden).meta.title, 'Orders & Submissions')
})

test('B users and card operators keep their existing routes; absent capabilities are never added', () => {
  const input = groupSubmissionWorkspaceRoutes([workspace], t(zh))
  assert.deepEqual(mergeAdminOrderRoutes(input, ['nxr_agent'], t(zh)), input)
  assert.deepEqual(input[0].children.filter(route => !route.hidden).map(route => route.path), ['orders','batches','addresses'])
  assert.deepEqual(mergeAdminOrderRoutes(input, ['admin'], t(zh)), input)
  const cards = [{ path: '/nxr/cards', children: [{ path: 'entries', component: 'nxr/entries/index' }] }]
  assert.deepEqual(mergeAdminOrderRoutes(cards, ['nxr_card_admin'], t(zh)), cards)
  assert.deepEqual(mergeAdminOrderRoutes(cards, ['admin'], t(zh)), cards)
  const orderOnly = mergeAdminOrderRoutes([orders], ['admin'], t(zh))
  assert.equal(flatten(orderOnly).some(route => route.name === 'NxrAdminSubmissions'), false)
  assert.equal(orderOnly[0].children[0].meta.adminOrderWorkspaceAvailable, false)
})

test('hidden source entries remain hidden when grouped with unrelated routes', () => {
  const unrelated = { path: '/system', component: 'Layout', children: [{ path: 'user', component: 'system/user/index' }] }
  const source = addPartnerFulfillmentRoute([{ ...orders, hidden: true }, unrelated])
  const routes = mergeAdminOrderRoutes(source, ['admin'], t(zh))
  assert.equal(routes.find(route => route.name === 'NxrOrderManagement').hidden, true)
  assert.deepEqual(routes.find(route => route.path === '/system'), unrelated)
})

test('old administrator links still reach the right records without loops or losing company scope', async () => {
  const map = route => ({ ...route, component: { render: () => null }, children: route.children?.map(map) })
  const router = createRouter({ history: createMemoryHistory(), routes: mergeAdminOrderRoutes(prepared(), ['admin'], t(zh)).map(map) })
  router.beforeEach(to => adminOrderLocation(to) || legacyWorkspaceLocation(to) || true)
  await router.push(`${WORKSPACE_ROOT}/batches`)
  assert.equal(router.currentRoute.value.path, ADMIN_BATCHES_PATH)
  await router.push(`${WORKSPACE_ROOT}?tab=batches&company=22&batch=MB-123#tracking`)
  assert.equal(router.currentRoute.value.path, workspacePath('batches'))
  assert.deepEqual(router.currentRoute.value.query, { company:'22', batch:'MB-123' })
  assert.equal(router.currentRoute.value.hash, '#tracking')
  await router.push(`${ADMIN_ORDERS_PATH}?batchId=24`)
  assert.equal(router.currentRoute.value.path, ADMIN_BATCHES_PATH)
  assert.equal(router.currentRoute.value.query.batchId, '24')
  await router.push(`${ADMIN_RETURNS_PATH}?tab=submissions&batchId=24`)
  assert.equal(router.currentRoute.value.path, ADMIN_BATCHES_PATH)
  await router.push(`${ADMIN_RETURNS_PATH}?tab=returns`)
  assert.equal(router.currentRoute.value.meta.adminView, 'returns')
  await router.push('/nxr/orders?orderId=31&section=cards')
  assert.equal(router.currentRoute.value.name, 'NxrOrders')
  assert.deepEqual(router.currentRoute.value.query, { orderId:'31', section:'cards' })
  await router.push('/nxr/partners?company=22')
  assert.equal(router.currentRoute.value.name, 'NxrWorkspacePartners')
  await router.push('/nxr/submissions/customers')
  assert.equal(router.currentRoute.value.name, 'NxrCustomers')
  await router.push('/nxr/agent-workbench?tab=addresses&company=22')
  assert.equal(router.currentRoute.value.path, workspacePath('addresses'))
  assert.equal(router.currentRoute.value.query.company, '22')
  await router.push(`${workspacePath('orders')}?company=22&record=42`)
  assert.equal(router.currentRoute.value.name, 'NxrWorkspaceOrders')
  assert.equal(router.currentRoute.value.meta.activeMenu, ADMIN_ORDERS_PATH)
})

test('administrator redirect policy never changes B-side navigation', () => {
  for (const path of [workspacePath('batches'), ADMIN_ORDERS_PATH, ADMIN_RETURNS_PATH]) {
    assert.equal(adminOrderLocation({ path, query: { batchId:'24', tab:'submissions' }, meta: {} }), null)
  }
})
