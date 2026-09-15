import assert from 'node:assert/strict'
import test from 'node:test'
import { createRouter, createMemoryHistory } from 'vue-router'
import {
  WORKSPACE_ROOT, WORKSPACE_VIEWS, workspacePath, resolveWorkspaceView,
  groupSubmissionWorkspaceRoutes, legacyWorkspaceLocation, workspaceSidebarTarget
} from '../src/utils/submissionWorkspace.js'
import en from '../src/i18n/messages/en.js'
import zh from '../src/i18n/messages/zh-CN.js'

const workspace = { path: 'nxr/submission-workbench', name: 'NxrSubmissionWorkbench', component: 'nxr/agent-workbench/index', meta: { title: '送评工作台' } }
const partners = { path: 'nxr/partners', name: 'NxrPartners', component: 'nxr/partners/index', meta: { title: '子代理管理' } }
const wrapper = entry => ({ path: '/', component: 'Layout', name: '', children: [structuredClone(entry)] })
const translate = locale => key => key.split('.').reduce((value, part) => value[part], locale)

function routerFor(source) {
  const mapped = groupSubmissionWorkspaceRoutes(source, translate(en))
  function withComponents(route) {
    return { ...route, component: { render: () => null }, children: route.children?.map(withComponents) }
  }
  const router = createRouter({ history: createMemoryHistory(), routes: mapped.map(withComponents) })
  router.beforeEach(to => router.hasRoute('NxrWorkspaceClients') ? legacyWorkspaceLocation(to) || true : true)
  return router
}

test('server Layout wrappers become one translated workspace with unique names and aliases', () => {
  const source = [wrapper(partners), wrapper(workspace)]
  const original = structuredClone(source)
  const result = groupSubmissionWorkspaceRoutes(source, translate(en))
  assert.equal(result.length, 1)
  const parent = result[0]
  assert.equal(parent.path, WORKSPACE_ROOT)
  assert.equal(parent.component, 'Layout')
  assert.equal(parent.alwaysShow, true)
  assert.equal(parent.redirect, workspacePath('clients'))
  assert.equal(parent.meta.title, 'Submission Workspace')
  assert.deepEqual(parent.children.map(child => child.path), ['partners', ...WORKSPACE_VIEWS])
  assert.equal(new Set(parent.children.map(child => child.name)).size, 7)
  assert.deepEqual(parent.children.flatMap(child => child.alias || []), ['/nxr/partners', '/nxr/agent-workbench'])
  for (const child of parent.children.slice(1)) {
    assert.equal(child.meta.workspaceView, child.path)
    assert.equal(child.meta.activeMenu, workspacePath(child.path))
    assert.equal(child.component, workspace.component)
  }
  assert.equal(parent.children[1].meta.title, 'Customer Files')
  assert.equal(groupSubmissionWorkspaceRoutes(source, translate(zh))[0].children[1].meta.title, '客户档案')
  assert.deepEqual(source, original)
})

test('bound partner receives only existing workspace capability, and management-only never gains it', () => {
  const boundPartner = groupSubmissionWorkspaceRoutes([wrapper(workspace)])[0]
  assert.deepEqual(boundPartner.children.map(child => child.path), WORKSPACE_VIEWS)
  assert.ok(boundPartner.children.every(child => child.component !== partners.component))
  const managementOnly = groupSubmissionWorkspaceRoutes([wrapper(partners)])[0]
  assert.deepEqual(managementOnly.children.map(child => child.path), ['partners'])
  assert.equal(managementOnly.redirect, workspacePath('partners'))
  assert.deepEqual(groupSubmissionWorkspaceRoutes([]), [])
})

test('nested or flat authorized shapes preserve unrelated siblings, order, and hidden status', () => {
  const unrelated = { path: '/system', component: 'Layout', children: [{ path: 'user', component: 'system/user/index' }] }
  const mixed = { path: '/nxr', component: 'Layout', children: [partners, { path: 'orders', component: 'nxr/orders/index' }] }
  const result = groupSubmissionWorkspaceRoutes([unrelated, mixed, { ...workspace, path: '/nxr/submission-workbench' }])
  assert.equal(result.length, 3)
  assert.deepEqual(result[0], unrelated)
  assert.equal(result[1].path, WORKSPACE_ROOT)
  assert.deepEqual(result[2].children, [{ path: 'orders', component: 'nxr/orders/index' }])
  const hidden = groupSubmissionWorkspaceRoutes([{ ...wrapper(workspace), hidden: true }])[0]
  assert.equal(hidden.hidden, true)
  assert.ok(hidden.children.every(child => child.hidden))
})

test('route metadata is authoritative and unknown or malformed legacy views fall back safely', () => {
  assert.equal(resolveWorkspaceView({ meta: { workspaceView: 'batches' }, query: { tab: 'clients' } }), 'batches')
  assert.equal(resolveWorkspaceView({ query: { tab: 'returns' } }), 'returns')
  for (const tab of ['partners', 'missing', ['wallet'], null]) {
    assert.equal(resolveWorkspaceView({ query: { tab } }), 'clients')
  }
  assert.equal(resolveWorkspaceView(), 'clients')
  assert.equal(workspacePath('../system/user'), workspacePath('clients'))
})

test('legacy bookmarks preserve company and hash, resolve the intended view, and do not loop', async () => {
  const router = routerFor([wrapper(partners), wrapper(workspace)])
  for (const path of [WORKSPACE_ROOT, '/nxr/agent-workbench']) {
    await router.push(`${path}?tab=addresses&company=20#contact`)
    assert.equal(router.currentRoute.value.path, workspacePath('addresses'))
    assert.deepEqual(router.currentRoute.value.query, { company: '20' })
    assert.equal(router.currentRoute.value.hash, '#contact')
  }
  await router.push('/nxr/partners?company=20')
  assert.equal(router.currentRoute.value.name, 'NxrWorkspacePartners')
  assert.equal(router.currentRoute.value.meta.activeMenu, workspacePath('partners'))
  await router.push(`${WORKSPACE_ROOT}?tab=invalid&company=20`)
  assert.equal(router.currentRoute.value.name, 'NxrWorkspaceClients')
  assert.deepEqual(router.currentRoute.value.query, { company: '20' })
})

test('management-only parent lands in partners without fabricating customer access', async () => {
  const router = routerFor([wrapper(partners)])
  await router.push(`${WORKSPACE_ROOT}?company=20`)
  assert.equal(router.currentRoute.value.name, 'NxrWorkspacePartners')
  assert.equal(router.hasRoute('NxrWorkspaceClients'), false)
})

test('sidebar retains company between workspace menus without carrying filters or leaking to other menus', () => {
  const route = { path: workspacePath('clients'), query: { company: '20', tab: 'clients', search: 'private-name' } }
  assert.deepEqual(workspaceSidebarTarget(workspacePath('batches'), route), { path: workspacePath('batches'), query: { company: '20' } })
  assert.deepEqual(workspaceSidebarTarget(workspacePath('partners'), route), { path: workspacePath('partners'), query: { company: '20' } })
  assert.equal(workspaceSidebarTarget('/system/user', route), '/system/user')
  assert.equal(workspaceSidebarTarget(workspacePath('wallet'), { ...route, query: {} }), workspacePath('wallet'))
  assert.equal(workspaceSidebarTarget(workspacePath('wallet'), { ...route, query: { company: ['20', '21'] } }), workspacePath('wallet'))
  assert.deepEqual(workspaceSidebarTarget('/system/user', route, { page: '1' }), { path: '/system/user', query: { page: '1' } })
})
