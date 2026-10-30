import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFileSync } from 'node:fs'
import * as vue from 'vue'
import { parse, compileScript } from '@vue/compiler-sfc'
import { transformSync } from 'esbuild'
import { partnerReturnReadinessLabel } from '../src/views/nxr/orders/partnerReturnLabels.js'

const emptyPage = () => ({ items: [], page: 1, pageSize: 20, total: 0 })
function panel(name, api, props = {}) {
  const file = new URL(`../src/views/nxr/agent-workbench/components/${name}.vue`, import.meta.url)
  const descriptor = parse(readFileSync(file, 'utf8')).descriptor
  const code = transformSync(compileScript(descriptor, { id: name }).content, { loader: 'ts', format: 'cjs' }).code
  const module = { exports: {} }, scope = vue.effectScope()
  const require = path => {
    if (path === 'vue') return { ...vue, onMounted: () => {}, onBeforeUnmount: () => {} }
    if (path === '@/i18n') return { tx: text => text }
    if (path === '@/utils/secureUuid') return { secureUuid: () => 'fixture-id' }
    if (path.endsWith('agentWorkbench')) return {
      useAgentApi: () => api, emptyAgentPage: emptyPage, emptyAgentAddress: () => ({}),
      useAgentActions: () => { const error = vue.ref(''); return { busy: vue.ref(false), error, success: vue.ref(''), run: async (_name, _payload, request, apply) => { try { const result = await request('fixture-key'); apply(result); return true } catch (e) { error.value = e.message; return false } } } }
    }
    if (path.endsWith('.vue')) return {}
    throw new Error(`Unexpected import: ${path}`)
  }
  vm.runInNewContext(code, { module, exports: module.exports, require, document: { activeElement: null }, HTMLElement: class {} })
  const state = scope.run(() => module.exports.default.setup(props, { expose() {}, emit() {} }))
  return { state, stop: () => scope.stop() }
}

test('B intake receives an order count and requires explicit exception resolution', async () => {
  const payloads = []
  const fixture = { intake: { id: 7, statusCode: 'exception', expectedCardCount: 2, exceptionCardCount: 1 }, cards: [], events: [] }
  const api = { fetchAgentIntake: async () => fixture, receiveAgentOrder: async (_id, data) => { payloads.push(JSON.parse(JSON.stringify(data))); return fixture } }
  const view = panel('AgentIntakesPanel', api, { legacyOnly: true })
  try {
    await view.state.show(7)
    view.state.receivedCount.value = 2
    await view.state.receive()
    assert.equal(payloads.length, 0)
    view.state.resolveExceptions.value = true
    view.state.receiveNote.value = 'Resolved on receipt'
    await view.state.receive()
    assert.deepEqual(payloads, [{ receivedCount: 2, note: 'Resolved on receipt', resolveExceptions: true }])
  } finally { view.stop() }
})

test('B customer return sends only the selected cards and preserves partial shipments', async () => {
  const payloads = []
  const api = { fetchAgentCards: async () => emptyPage(), fetchAgentShipments: async () => emptyPage(), createAgentShipment: async data => { payloads.push(JSON.parse(JSON.stringify(data))); return { shipment: { id: 2 }, cards: [], events: [] } } }
  const view = panel('AgentReturnsPanel', api)
  try {
    view.state.form.clientId = 3
    await vue.nextTick()
    view.state.selectedClient.value = { id: 3 }
    view.state.form.carrierName = 'Carrier'; view.state.form.trackingNumber = 'Tracking-1'
    view.state.selected.value = { 11: { id: 11, clientId: 3, statusCode: 'returned' }, 13: { id: 13, clientId: 3, statusCode: 'returned' } }
    await view.state.createShipment()
    assert.deepEqual(payloads[0].cardIds, [11, 13])
    assert.equal(payloads[0].trackingNumber, 'Tracking-1')
  } finally { view.stop() }
})

test('NXR return submits only selected card IDs, without a per-card check', async () => {
  const file = new URL('../src/views/nxr/orders/components/PartnerReturnPanel.vue', import.meta.url)
  const descriptor = parse(readFileSync(file, 'utf8')).descriptor
  const code = transformSync(compileScript(descriptor, { id: 'partner-returns' }).content, { format: 'cjs' }).code
  const module = { exports: {} }, scope = vue.effectScope(), sent = []
  const items = [{ orderItemId: 51, orderId: 8, batchId: 2, companyId: 4, eligible: true, returnAddress: 'A' }, { orderItemId: 52, orderId: 8, batchId: 2, companyId: 4, eligible: true, returnAddress: 'A' }]
  const api = { listPartnerReturnCompanies: async () => ({ data: [] }), listPartnerReturnCards: async () => ({ data: { items, total: 2 } }), createPartnerReturn: async value => { sent.push(JSON.parse(JSON.stringify(value))); return { data: { id: 1 } } } }
  const require = path => {
    if (path === 'vue') return { ...vue, onBeforeUnmount: () => {} }
    if (path === '@/i18n') return { tx: text => text, activeLocale: () => 'en', localizeBackendMessage: text => text }
    if (path === '@/plugins/auth') return { hasPermiOr: () => true }
    if (path === '@/utils/secureUuid') return { secureUuid: () => 'fixture-key' }
    if (path.endsWith('partnerReturnLabels')) return { partnerReturnReadinessLabel }
    if (path === 'element-plus') return { ElMessage: { success: () => {} }, ElMessageBox: {} }
    if (path === '@/api/nxr/partnerReturns') return api
    throw new Error(`Unexpected import: ${path}`)
  }
  vm.runInNewContext(code, { module, exports: module.exports, require })
  const state = scope.run(() => module.exports.default.setup({}, { expose() {}, emit() {} }))
  try {
    await Promise.resolve(); await Promise.resolve()
    state.selectionChanged([items[0]])
    state.openCreate()
    state.createForm.value.carrierName = 'Carrier'; state.createForm.value.trackingNumber = 'Tracking-1'
    await state.submitParcel()
    assert.deepEqual(sent[0].orderItemIds, [51])
    assert.equal(sent[0].requestKey, 'fixture-key')
    assert.equal('qualityPassed' in sent[0], false)
  } finally { scope.stop() }
})
