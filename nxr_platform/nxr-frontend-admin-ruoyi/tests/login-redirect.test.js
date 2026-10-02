import test from 'node:test'
import assert from 'node:assert/strict'
import { createMemoryHistory, createRouter } from 'vue-router'
import { browserReloginUrl, loginLocation, loginDestination, safeLoginTarget } from '../src/utils/loginRedirect.js'

test('expired sessions retain the order, selected section and hash on local routes', () => {
  const target = '/nxr/submissions/orders?orderId=40&section=finance#history'
  assert.deepEqual(loginLocation(target), { path: '/login', query: { redirect: target }, replace: true })
  assert.equal(browserReloginUrl({ pathname: '/nxr/submissions/orders', search: '?orderId=40&section=finance', hash: '#history' }),
    '/login?redirect=' + encodeURIComponent(target))
})

test('production subpath is kept outside the router redirect', () => {
  assert.equal(browserReloginUrl({ pathname: '/java-stage-admin/nxr/cards/approved-entries', search: '?status=approved', hash: '' }, '/java-stage-admin/'),
    '/java-stage-admin/login?redirect=' + encodeURIComponent('/nxr/cards/approved-entries?status=approved'))
})

test('login loops and external targets fall back to the authorized landing page', () => {
  for (const target of ['//other.example', 'https://other.example', '/login?redirect=/index', '/\\other.example', undefined])
    assert.equal(safeLoginTarget(target), '/index')
})

test('sign-in resolves embedded query parameters and preserves legacy split-query bookmarks', () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/index', component: {} }, { path: '/nxr/submissions/orders', component: {} }
  ] })
  const expected = { path: '/nxr/submissions/orders', query: { orderId: '40', section: 'finance' }, hash: '#history' }
  assert.deepEqual(loginDestination(router, '/nxr/submissions/orders?orderId=40&section=finance#history'), expected)
  assert.deepEqual(loginDestination(router, '/nxr/submissions/orders?orderId=40#history', { section: 'finance' }), expected)
})
