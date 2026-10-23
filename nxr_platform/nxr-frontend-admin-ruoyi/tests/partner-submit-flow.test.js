import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import * as vue from 'vue'
import { parse, compileScript } from '@vue/compiler-sfc'
import { transformSync } from 'esbuild'
import * as orderHelpers from '../src/views/nxr/agent-workbench/lib/workbenchOrders.js'

function deferred() { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
function setupPanel(name, api, props) {
  api.addressVersion ||= vue.ref(0)
  const descriptor = parse(readFileSync(new URL(`../src/views/nxr/agent-workbench/components/${name}.vue`, import.meta.url), 'utf8')).descriptor
  const compiled = transformSync(compileScript(descriptor, { id: name }).content, { loader: 'ts', format: 'cjs' }).code
  const hooks = {}, scope = vue.effectScope(), module = { exports: {} }, emitted = []
  const require = path => {
    if (path === 'vue') return { ...vue, onMounted: callback => { hooks.mount = callback }, onBeforeUnmount: callback => { hooks.unmount = callback } }
    if (path === '@/i18n') return { tx: value => value, localizeBackendMessage: value => value, localizeOrderTerms: value => value }
    if (path === 'vue-router') return { useRouter: () => ({ push() {} }) }
    if (path === '@/utils/submissionWorkspace') return { workspacePath: tab => `/nxr/submission-workbench/${tab}` }
    if (path.endsWith('workbenchOrders')) return orderHelpers
    if (path.endsWith('agentWorkbench')) return {
      emptyAgentPage: () => ({ items: [], total: 0, page: 1, pageSize: 20 }), useAgentApi: () => api,
      emptyAgentAddress: () => ({}),
      useAgentActions: () => {
        const error = vue.ref('')
        return { busy: api.busy, error, success: vue.ref(''), run: async (name, payload, request, apply) => {
          try { const result = await request('local-test-request'); apply(result); return true }
          catch (e) { error.value = e.message; return false }
        } }
      },
    }
    if (path.endsWith('.vue')) return {}
    throw new Error('Unexpected dependency: ' + path)
  }
  vm.runInNewContext(compiled, { module, exports: module.exports, require })
  const state = scope.run(() => module.exports.default.setup(props, { expose() {}, emit: (...event) => emitted.push(event) }))
  return { state, hooks, emitted, stop() { hooks.unmount?.(); scope.stop() } }
}
const row = { id: 12, orderNo: 'NXR-000012', clientName: 'Fixture', canSubmit: true, cardCount: 2 }
const serviceTerms = { termsVersion: 'test-v2', termsText: 'Service terms\nCustom text kept exactly.', turnaroundText: '20 working days' }

test('initial route selections finish before the operator can change or submit them', async () => {
  const pending = deferred(), detailRequested = deferred(), api = { busy: vue.ref(false), fetchWorkbenchOrders: async () => ({ items: [row], total: 1, page: 1, pageSize: 20 }), fetchWorkbenchOrder: () => { detailRequested.resolve(); return pending.promise } }
  const panel = setupPanel('AgentSubmitCreatePanel', api, { initialOrderIds: [12] })
  try {
    const mounting = panel.hooks.mount()
    await detailRequested.promise
    assert.equal(panel.state.loading.value, false)
    assert.equal(panel.state.initializing.value, true)
    panel.state.select(row, true)
    assert.equal(panel.state.selectedOrders.value.length, 0)
    pending.resolve({ order: row })
    await mounting
    assert.equal(panel.state.initializing.value, false)
    assert.deepEqual(Array.from(panel.state.selectedOrders.value, order => order.id), [12])
    panel.state.select(row, false)
    assert.equal(panel.state.selectedOrders.value.length, 0)
  } finally { panel.stop() }
})

test('leaving during preselection invalidates delayed responses', async () => {
  const pending = deferred(), detailRequested = deferred(), api = { busy: vue.ref(false), fetchWorkbenchOrders: async () => ({ items: [row], total: 1, page: 1, pageSize: 20 }), fetchWorkbenchOrder: () => { detailRequested.resolve(); return pending.promise } }
  const panel = setupPanel('AgentSubmitCreatePanel', api, { initialOrderIds: [12] })
  const mounting = panel.hooks.mount()
  await detailRequested.promise
  panel.stop()
  pending.resolve({ order: row })
  await mounting
  assert.equal(panel.state.selectedOrders.value.length, 0)
})

test('Submit retains non-USD weight service metadata and never requests a payment quote', async () => {
  const weight = { optionCode: 'weight_international', displayName: 'Tracked weight service', currencyCode: 'EUR' }
  const fixed = { optionCode: 'fixed_tracked', displayName: 'Tracked fixed service', currencyCode: 'USD' }
  let choices = [weight, fixed], submitted
  const calls = [], api = {
    busy: vue.ref(false), fetchLogisticsOptions: async (country, count) => { calls.push({ country, count }); return choices },
    fetchApplicationConfig: async () => serviceTerms,
    createAgentSubmission: async payload => { submitted = payload; return { batchNo: 'MB-LOCAL', intakeIds: payload.intakeIds } },
  }
  const panel = setupPanel('AgentSubmissionForm', api, { orders: [row] })
  try {
    panel.state.addresses.value = [{ id: 3, country: 'FR', defaultAddress: true }]
    panel.state.addressId.value = 3
    await vue.nextTick(); await Promise.resolve(); await Promise.resolve()
    assert.equal(panel.state.selectedService.value.currencyCode, 'EUR')
    choices = [fixed, weight]
    await panel.state.loadServices()
    assert.equal(panel.state.selectedService.value.optionCode, weight.optionCode)
    panel.state.batchName.value = 'Local no-payment Submit'
    await panel.state.loadTerms()
    panel.state.acceptedTerms.value = true
    await panel.state.submit()
    assert.equal(submitted.returnShippingOptionCode, weight.optionCode)
    assert.equal(submitted.currencyCode, 'EUR')
    assert.equal('quotedTotalAmount' in submitted, false)
    assert.equal('quotedCurrencyCode' in submitted, false)
    assert.equal(submitted.termsAccepted, true)
    assert.equal(submitted.termsVersion, serviceTerms.termsVersion)
    assert.equal(submitted.termsText, serviceTerms.termsText)
    assert.equal(submitted.turnaroundText, serviceTerms.turnaroundText)
    assert.ok(calls.every(call => call.country === 'FR' && call.count === 2))
    assert.equal(panel.emitted[0][0], 'created')
  } finally { panel.stop() }
})

function termsPanel(overrides = {}, props = { orders: [row] }) {
  const writes = []
  const api = {
    busy: vue.ref(false), fetchApplicationConfig: async () => serviceTerms,
    fetchLogisticsOptions: async () => [{ optionCode: 'tracked', currencyCode: 'USD', displayName: 'Tracked' }],
    createAgentSubmission: async payload => { writes.push(payload); return { batchNo: 'LOCAL', intakeIds: payload.intakeIds } },
    ...overrides
  }
  const panel = setupPanel('AgentSubmissionForm', api, props)
  panel.state.addresses.value = [{ id: 3, country: 'US' }]
  panel.state.addressId.value = 3
  panel.state.batchName.value = 'Terms acceptance fixture'
  return { ...panel, writes }
}

test('Submit is blocked until the displayed terms have been explicitly accepted', async () => {
  const panel = termsPanel()
  try {
    await panel.state.loadServices()
    await panel.state.loadTerms()
    assert.equal(panel.state.ready.value, false)
    await panel.state.submit()
    assert.equal(panel.writes.length, 0)
    panel.state.acceptedTerms.value = true
    assert.equal(panel.state.ready.value, true)
    await panel.state.submit()
    assert.equal(panel.writes.length, 1)
    assert.equal(panel.writes[0].termsText, serviceTerms.termsText)
  } finally { panel.stop() }
})

test('reloading terms invalidates consent, and an older response cannot replace the current text', async () => {
  const old = deferred(), latest = deferred(); let calls = 0
  const panel = termsPanel({ fetchApplicationConfig: () => (++calls === 1 ? old : latest).promise })
  try {
    panel.state.acceptedTerms.value = true
    const loadingOld = panel.state.loadTerms()
    assert.equal(panel.state.acceptedTerms.value, false)
    assert.equal(panel.state.ready.value, false)
    const loadingLatest = panel.state.loadTerms()
    latest.resolve({ ...serviceTerms, termsVersion: 'new-version' })
    await loadingLatest
    panel.state.acceptedTerms.value = true
    old.resolve(serviceTerms)
    await loadingOld
    assert.equal(panel.state.terms.value.termsVersion, 'new-version')
    assert.equal(panel.state.acceptedTerms.value, true)
  } finally { panel.stop() }
})

test('missing terms or a failed request cannot enable Submit', async () => {
  for (const fetchApplicationConfig of [async () => ({ termsVersion: 'v1', termsText: '' }), async () => { throw new Error('Unavailable') }]) {
    const panel = termsPanel({ fetchApplicationConfig })
    try {
      await panel.state.loadServices()
      await panel.state.loadTerms()
      panel.state.acceptedTerms.value = true
      await panel.state.submit()
      assert.equal(panel.state.ready.value, false)
      assert.ok(panel.state.termsError.value)
      assert.equal(panel.writes.length, 0)
    } finally { panel.stop() }
  }
})

test('changing selected orders requires a fresh consent for the new item list', async () => {
  const props = vue.reactive({ orders: [row] }), panel = termsPanel({}, props)
  try {
    await panel.state.loadServices()
    await panel.state.loadTerms()
    panel.state.acceptedTerms.value = true
    props.orders = [{ ...row, id: 13 }]
    await vue.nextTick()
    assert.equal(panel.state.acceptedTerms.value, false)
    await panel.state.submit()
    assert.equal(panel.writes.length, 0)
  } finally { panel.stop() }
})

test('leaving Submit prevents delayed terms responses from updating the disposed form', async () => {
  const pending = deferred(), panel = termsPanel({ fetchApplicationConfig: () => pending.promise })
  const loading = panel.state.loadTerms()
  panel.stop()
  pending.resolve(serviceTerms)
  await loading
  assert.equal(panel.state.terms.value, null)
})

test('a new default address updates an untouched Submit but preserves its explicit address choice', async () => {
  let addresses = [{ id: 3, country: 'US', defaultAddress: true }, { id: 4, country: 'FR', defaultAddress: false }]
  const panel = termsPanel({ fetchCustomerAddresses: async () => addresses })
  try {
    await panel.state.reloadAddresses()
    await vue.nextTick()
    assert.equal(panel.state.addressId.value, 3)
    addresses = addresses.map(row => ({ ...row, defaultAddress: row.id === 4 }))
    await panel.state.reloadAddresses()
    assert.equal(panel.state.addressId.value, 4)
    panel.state.addressTouched.value = true
    panel.state.addressId.value = 3
    await panel.state.reloadAddresses()
    assert.equal(panel.state.addressId.value, 3)
  } finally { panel.stop() }
})

test('address-list checkbox sends only its ID and changes the default only after saving succeeds', async () => {
  let addresses = [{ id: 3, label: 'Old', defaultAddress: true }, { id: 4, label: 'New', defaultAddress: false }]
  const pending = deferred(), calls = []
  const api = { busy: vue.ref(false), fetchCustomerAddresses: async () => addresses, setDefaultAddress: async (...args) => { calls.push(args); await pending.promise; addresses = addresses.map(row => ({ ...row, defaultAddress: row.id === 4 })); return addresses[1] } }
  const panel = setupPanel('AgentAddressesPanel', api, {})
  try {
    await panel.state.load()
    const saving = panel.state.setDefault(panel.state.rows.value[1])
    await panel.state.setDefault(panel.state.rows.value[1])
    assert.equal(panel.state.rows.value.find(row => row.defaultAddress).id, 3)
    assert.deepEqual(calls, [[4, 'local-test-request']])
    pending.resolve()
    await saving
    assert.equal(panel.state.rows.value.find(row => row.defaultAddress).id, 4)
    assert.equal(panel.state.rows.value.filter(row => row.defaultAddress).length, 1)
    await panel.state.setDefault(panel.state.rows.value[1])
    assert.equal(calls.length, 1)
    assert.equal(panel.state.settingDefaultId.value, null)
  } finally { panel.stop() }
})

test('a failed default-address save preserves the checked address and allows retry', async () => {
  const addresses = [{ id: 3, defaultAddress: true }, { id: 4, defaultAddress: false }]
  const panel = setupPanel('AgentAddressesPanel', { busy: vue.ref(false), fetchCustomerAddresses: async () => addresses, setDefaultAddress: async () => { throw new Error('Try again') } }, {})
  try {
    await panel.state.load()
    await panel.state.setDefault(panel.state.rows.value[1])
    assert.equal(panel.state.rows.value[0].defaultAddress, true)
    assert.equal(panel.state.rows.value[1].defaultAddress, false)
    assert.equal(panel.state.error.value, 'Try again')
    assert.equal(panel.state.settingDefaultId.value, null)
  } finally { panel.stop() }
})
