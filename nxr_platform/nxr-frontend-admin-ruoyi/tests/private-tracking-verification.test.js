import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFileSync } from 'node:fs'
import * as vue from 'vue'
import { parse, compileScript } from '@vue/compiler-sfc'
import { transformSync } from 'esbuild'

function deferred() { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
function setup(api) {
  const file = new URL('../../nxr-frontend-web/src/views/WebPrivateOrderTrackingView.vue', import.meta.url)
  const code = transformSync(compileScript(parse(readFileSync(file, 'utf8')).descriptor, { id: 'verification' }).content, { loader: 'ts', format: 'cjs' }).code
  const module = { exports: {} }, scope = vue.effectScope(), hooks = {}, props = vue.reactive({ token: 'link-token' })
  const require = path => {
    if (path === 'vue') return { ...vue, onMounted: () => {}, onBeforeMount: () => {}, onBeforeUnmount: fn => { hooks.unmount = fn } }
    if (path === 'vue-i18n') return { useI18n: () => ({ t: key => key, te: () => true, locale: vue.ref('en') }) }
    if (path.endsWith('/merchantBatches')) return api
    if (path.endsWith('/orderProgress')) return { orderDisplayStatus: value => value }
    if (path.endsWith('/privateOrderTracking')) return { privateOrderNumber: () => '', privateReturnSummary: () => ({}), privateTrackingStatus: value => value, privateReturnStatus: value => value, privateTimelineTitle: () => '', privateTrackingDate: () => '' }
    if (path.endsWith('.vue')) return {}
    throw new Error(path)
  }
  vm.runInNewContext(code, { module, exports: module.exports, require, setTimeout: () => 1, clearTimeout() {}, clearInterval() {} })
  const state = scope.run(() => module.exports.default.setup(props, { expose() {} }))
  return { state, props, stop() { hooks.unmount?.(); scope.stop() } }
}

test('before verification no order is fetched and failure exposes no details', async () => {
  let reads = 0, verified
  const panel = setup({ verifyPrivateOrderTracking: async (token, contact) => { verified = [token, contact]; throw new Error('wrong') }, fetchPrivateOrderTracking: async () => { reads++ } })
  try {
    assert.equal(reads, 0); assert.equal(panel.state.order.value, null)
    panel.state.contact.value = 'person@example.test'
    await panel.state.verify()
    assert.deepEqual(verified, ['link-token', 'person@example.test'])
    assert.equal(reads, 0); assert.equal(panel.state.error.value, true); assert.equal(panel.state.contact.value, ''); assert.equal(panel.state.accessToken.value, '')
  } finally { panel.stop() }
})

test('successful verification uses in-memory access and a rejected refresh clears the order', async () => {
  let reject = false, readArgs
  const panel = setup({ verifyPrivateOrderTracking: async () => ({ accessToken: 'session-only', expiresInSeconds: 900 }), fetchPrivateOrderTracking: async (...args) => { readArgs = args; if (reject) throw new Error('revoked'); return { orderNo: 'A', timeline: [], cards: [] } } })
  try {
    panel.state.contact.value = '123456'
    await panel.state.verify()
    assert.deepEqual(readArgs, ['link-token', 'session-only'])
    assert.equal(panel.state.contact.value, ''); assert.equal(panel.state.order.value.orderNo, 'A')
    reject = true; await panel.state.refresh()
    assert.equal(panel.state.order.value, null); assert.equal(panel.state.accessToken.value, ''); assert.equal(panel.state.error.value, true)
  } finally { panel.stop() }
})

test('a delayed verification from a previous link cannot reveal an order', async () => {
  const response = deferred(); let reads = 0
  const panel = setup({ verifyPrivateOrderTracking: () => response.promise, fetchPrivateOrderTracking: async () => { reads++ } })
  try {
    panel.state.contact.value = '123456'
    const pending = panel.state.verify()
    panel.props.token = 'different-link'; await vue.nextTick()
    response.resolve({ accessToken: 'stale', expiresInSeconds: 900 }); await pending
    assert.equal(reads, 0); assert.equal(panel.state.order.value, null); assert.equal(panel.state.accessToken.value, '')
  } finally { panel.stop() }
})

test('verification uses a POST body and order reads use fixed path with authorization headers', async () => {
  const file = new URL('../../nxr-frontend-web/src/lib/merchantBatches.ts', import.meta.url)
  const source = readFileSync(file, 'utf8')
  const lines = source.split('\n').filter(line => line.startsWith('export const verifyPrivateOrderTracking') || line.startsWith('export const fetchPrivateOrderTracking'))
  assert.equal(lines.length, 2)
  const js = transformSync(lines.join('\n').replaceAll('export const ', 'const ') + '\nglobalThis.api = { verifyPrivateOrderTracking, fetchPrivateOrderTracking }', { loader: 'ts' }).code
  const calls = [], context = { customerRequest: async (...args) => { calls.push(args) }, JSON }
  vm.runInNewContext(js, context)
  await context.api.verifyPrivateOrderTracking('link-secret', 'person@example.test')
  await context.api.fetchPrivateOrderTracking('link-secret', 'session-secret')
  assert.equal(calls[0][0], '/api/public/merchant-order-tracking/verify')
  assert.equal(calls[0][1].method, 'POST')
  assert.deepEqual(JSON.parse(calls[0][1].body), { token: 'link-secret', contact: 'person@example.test' })
  assert.equal(calls[1][0], '/api/public/merchant-order-tracking/order')
  assert.equal(calls[1][1].headers['X-NXR-Tracking-Token'], 'link-secret')
  assert.equal(calls[1][1].headers['X-NXR-Tracking-Access'], 'session-secret')
  assert.equal(calls[0][0].includes('person@example.test'), false)
  assert.equal(calls[1][0].includes('link-secret'), false)
})
