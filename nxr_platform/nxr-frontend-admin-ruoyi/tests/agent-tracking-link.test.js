import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFileSync } from 'node:fs'
import * as vue from 'vue'
import { parse, compileScript } from '@vue/compiler-sfc'
import { transformSync } from 'esbuild'

function setup(api, confirm = async () => {}) {
  const path = new URL('../src/views/nxr/agent-workbench/components/AgentTrackingLink.vue', import.meta.url)
  const code = transformSync(compileScript(parse(readFileSync(path, 'utf8')).descriptor, { id: 'tracking' }).content, { loader: 'ts', format: 'cjs' }).code
  const module = { exports: {} }, scope = vue.effectScope()
  const require = path => {
    if (path === 'vue') return { ...vue, onBeforeUnmount: () => {} }
    if (path === 'element-plus') return { ElMessageBox: { confirm } }
    if (path === '@/i18n') return { tx: value => value }
    if (path === '@/utils/copyText') return { copyText: async () => true }
    if (path.endsWith('agentWorkbench')) return { useAgentApi: () => api, privateTrackingUrl: value => value }
    throw new Error(path)
  }
  vm.runInNewContext(code, { module, exports: module.exports, require, Error })
  const state = scope.run(() => module.exports.default.setup({ orderId: 42 }, { expose() {} }))
  return { state, stop: () => scope.stop() }
}

test('existing link is never replaced merely by opening; cancelled replacement sends nothing', async () => {
  const calls = [], api = { fetchOrderTrackingState: async () => ({ active: true, version: 4, legacyActive: true }), replaceOrderTrackingLink: async (...args) => { calls.push(args) } }
  const panel = setup(api, async () => { throw new Error('cancel') })
  try { await panel.state.load(); assert.equal(panel.state.state.value.active, true); await panel.state.act('replace'); assert.deepEqual(calls, []) }
  finally { panel.stop() }
})

test('new link uses create-only, subsequent replacement carries the checked version', async () => {
  let current = { active: false, version: 0, legacyActive: false }; const calls = []
  const api = { fetchOrderTrackingState: async () => current,
    createOrderTrackingLink: async id => { calls.push(['create', id]); current = { ...current, active: true, version: 1 }; return { trackingUrl: 'https://public.example/track/new' } },
    replaceOrderTrackingLink: async (id, version) => { calls.push(['replace', id, version]); current = { ...current, version: 2 }; return { trackingUrl: 'https://public.example/track/next' } } }
  const panel = setup(api)
  try { await panel.state.load(); await panel.state.act('create'); assert.equal(panel.state.link.value, 'https://public.example/track/new'); await panel.state.act('replace'); assert.deepEqual(calls, [['create', 42], ['replace', 42, 1]]) }
  finally { panel.stop() }
})

test('stale create failure refreshes state and cannot accidentally replace the existing link', async () => {
  let current = { active: false, version: 0, legacyActive: false }, replaceCalls = 0
  const api = { fetchOrderTrackingState: async () => current,
    createOrderTrackingLink: async () => { current = { active: true, version: 1, legacyActive: false }; throw new Error('Already active') },
    replaceOrderTrackingLink: async () => { replaceCalls++ } }
  const panel = setup(api)
  try { await panel.state.load(); await panel.state.act('create'); assert.equal(panel.state.state.value.active, true); assert.equal(panel.state.error.value, 'Already active'); assert.equal(replaceCalls, 0) }
  finally { panel.stop() }
})

test('new and historical links are revoked separately after confirmation', async () => {
  let current = { active: true, version: 7, legacyActive: true }; const calls = []
  const api = { fetchOrderTrackingState: async () => current,
    revokeOrderTrackingLink: async (id, version) => { calls.push(['new', id, version]); current = { ...current, active: false, version: null } },
    revokeLegacyOrderTrackingLink: async id => { calls.push(['legacy', id]); current = { ...current, legacyActive: false } } }
  const panel = setup(api)
  try {
    await panel.state.load(); await panel.state.act('revoke')
    assert.deepEqual(calls, [['new', 42, 7]])
    assert.equal(panel.state.state.value.legacyActive, true)
    await panel.state.act('legacy')
    assert.deepEqual(calls, [['new', 42, 7], ['legacy', 42]])
  } finally { panel.stop() }
})

test('customer URLs preserve subpaths and reject unsafe or inaccessible addresses', async () => {
  const source = readFileSync(new URL('../src/views/nxr/agent-workbench/lib/agentWorkbench.ts', import.meta.url), 'utf8')
  const fn = source.slice(source.indexOf('export function privateTrackingUrl('), source.indexOf('export function normalizeAgentReturnScan('))
  const js = transformSync(fn.replace('export function', 'function'), { loader: 'ts', target: 'es2022' }).code
  const context = { URL, window: { location: { hostname: '192.168.1.7' } }, tx: value => value }
  vm.runInNewContext(js + '\nglobalThis.resolve = privateTrackingUrl', context)
  assert.equal(context.resolve('https://example.test/portal/track/abc-123'), 'https://example.test/portal/track/abc-123')
  assert.equal(context.resolve('https://example.test/customer.portal/track/abc-123'), 'https://example.test/customer.portal/track/abc-123')
  const fragmentLink = 'https://example.test/customer.portal/track#' + 'A'.repeat(43)
  assert.equal(context.resolve(fragmentLink), fragmentLink)
  assert.throws(() => context.resolve('https://example.test/track#bad'))
  for (const url of ['/track/abc', 'http://127.0.0.1:3000/track/abc', 'https://user:pass@example.test/track/abc', 'https://example.test/track/abc?foo=1', 'https://example.test/track/abc#x', 'https://example.test/x7k9m2q4r8v6c3p1/track/abc', 'javascript:alert(1)']) assert.throws(() => context.resolve(url))
  context.window.location.hostname = 'localhost'
  assert.equal(context.resolve('http://127.0.0.1:3000/track/abc'), 'http://127.0.0.1:3000/track/abc')
})
