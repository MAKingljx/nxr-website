import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFileSync } from 'node:fs'
import * as vue from 'vue'
import { parse, compileScript } from '@vue/compiler-sfc'
import { transformSync } from 'esbuild'

function fixture(available = true) {
  const file = new URL('../../nxr-frontend-web/src/components/PrivateTrackingPhoto.vue', import.meta.url)
  const code = transformSync(compileScript(parse(readFileSync(file, 'utf8')).descriptor, { id: 'photo' }).content, { loader: 'ts', format: 'cjs' }).code
  const module = { exports: {} }, scope = vue.effectScope(), calls = [], revoked = [], hooks = {}
  const require = path => {
    if (path === 'vue') return { ...vue, onBeforeUnmount: fn => { hooks.unmount = fn } }
    if (path === 'vue-i18n') return { useI18n: () => ({ t: value => value }) }
    if (path.endsWith('/api')) return { apiBaseUrl: 'https://customer.example' }
    throw new Error(path)
  }
  const fetch = async (url, options) => { calls.push({ url, options }); return { ok: true, blob: async () => new Blob(['photo']) } }
  vm.runInNewContext(code, { module, exports: module.exports, require, fetch, AbortController, URL: { createObjectURL: () => 'blob:private-photo', revokeObjectURL: url => revoked.push(url) } })
  const state = scope.run(() => module.exports.default.setup({ token: 'private-fixture-token', accessToken: 'access-fixture-token', cardId: 17, side: 'front', available }, { expose() {} }))
  return { state, calls, revoked, stop: () => { hooks.unmount?.(); scope.stop() } }
}

test('tracking photo uses the token header and no-store policy, not the URL or cookies', async () => {
  const view = fixture()
  try {
    await view.state.load()
    const request = view.calls.at(-1)
    assert.equal(request.url, 'https://customer.example/api/public/merchant-order-tracking/photos/17/front')
    assert.equal(request.url.includes('private-fixture-token'), false)
    assert.equal(request.options.headers['X-NXR-Tracking-Token'], 'private-fixture-token')
    assert.equal(request.options.headers['X-NXR-Tracking-Access'], 'access-fixture-token')
    assert.equal(request.options.cache, 'no-store')
    assert.equal(request.options.credentials, 'omit')
    assert.equal(request.options.referrerPolicy, 'no-referrer')
    assert.equal(view.state.url.value, 'blob:private-photo')
  } finally { view.stop() }
  assert.ok(view.revoked.includes('blob:private-photo'))
})

test('missing photos do not make a network request', async () => {
  const view = fixture(false)
  try { await view.state.load(); assert.equal(view.calls.length, 0); assert.equal(view.state.url.value, '') }
  finally { view.stop() }
})
