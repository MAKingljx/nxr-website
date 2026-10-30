import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import * as vue from 'vue'
import { parse, compileScript } from '@vue/compiler-sfc'
import { transformSync } from 'esbuild'
import * as grading from '../src/views/nxr/orders/gradingForm.js'

function deferred() { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }

test('B grading follows the existing receipt and shipped-card states', () => {
  for (const status of ['received', 'grading', 'review', 'quality_check', 'quality_hold']) assert.equal(grading.partnerGradingBlock(status, 'received'), '')
  for (const status of ['awaiting_inbound', 'inbound_shipped', 'intake_exception', 'completed', 'cancelled']) assert.notEqual(grading.partnerGradingBlock(status, 'received'), '')
  assert.notEqual(grading.partnerGradingBlock('grading', 'return_shipped'), '')
  assert.notEqual(grading.partnerGradingBlock('grading', 'delivered'), '')
})
function setupPanel(api = {}) {
  const descriptor = parse(readFileSync(new URL('../src/views/nxr/orders/components/OrderWorkbenchPanel.vue', import.meta.url), 'utf8')).descriptor
  const compiled = transformSync(compileScript(descriptor, { id: 'grading' }).content, { format: 'cjs' }).code
  const hooks = {}, scope = vue.effectScope(), module = { exports: {} }
  const require = path => {
    if (path === 'vue') return { ...vue, getCurrentInstance: () => ({ proxy: { useDict: () => ({ nxr_vintage_classification: vue.ref([]) }), $tx: value => value } }), onDeactivated: callback => { hooks.deactivate = callback }, onBeforeUnmount: callback => { hooks.unmount = callback } }
    if (path === 'vue-i18n') return { useI18n: () => ({ t: key => key }) }
    if (path.endsWith('gradingForm')) return grading
    if (path === '@/store/modules/user') return () => ({ id: 1 })
    if (path === '@/api/nxr/orderWorkbench') return { getOrderWorkbench: async () => ({ data: {} }), ...api }
    if (path === '@/api/nxr/entries') return api
    return {}
  }
  // Timers are driven explicitly, so delayed-response tests never depend on wall-clock timing.
  vm.runInNewContext(compiled, { module, exports: module.exports, require, setTimeout: () => 1, clearTimeout() {} })
  const state = scope.run(() => module.exports.default.setup({ orderId: 53, orderItems: [] }, { expose() {} }))
  return { state, stop() { hooks.unmount?.(); scope.stop() } }
}

test('Python defaults and all four graded categories use the same four score fields', () => {
  const form = grading.emptySubmission()
  assert.deepEqual(grading.scorePayload(form), { centeringScore: 1, edgesScore: 1, cornersScore: 1, surfaceScore: 1 })
  for (const cardCategory of ['trading_card', 'sports_card', 'celebrity_card', 'movie_film']) {
    const fields = grading.requiredSubmissionFields({ ...form, cardCategory })
    assert.ok(grading.scoreFields.every(({ key }) => fields.includes(key)))
    assert.equal(fields.includes('sportsType'), cardCategory === 'sports_card')
    assert.equal(fields.includes('groupName'), cardCategory === 'celebrity_card')
    assert.equal(fields.includes('movieName'), cardCategory === 'movie_film')
    assert.equal(fields.includes('brandName'), cardCategory !== 'movie_film')
  }
})

test('valid fractional scores keep their precision in preview and save payloads', () => {
  const form = { ...grading.emptySubmission(), centeringScore: 8.49, edgesScore: 9.34, cornersScore: 9.74, surfaceScore: 9.999 }
  assert.equal(grading.validScores(form), true)
  for (const { key } of grading.scoreFields) {
    assert.equal(grading.scorePayload(form)[key], form[key])
    assert.equal(grading.submissionPayload(form)[key], form[key])
  }
})

test('non-graded products cannot send stale numeric grades or category-specific fields', () => {
  for (const productType of ['merch_product', 'vintage_product']) {
    const form = { ...grading.emptySubmission(), productType, cardCategory: 'sports_card', sportsType: 'Soccer', groupName: 'Group', movieName: 'Movie', vintageClassification: 'A', merchDescription: 'Description', finalGradeValue: 10, finalGradeLabel: 'Pristine 10' }
    const fields = grading.requiredSubmissionFields(form), payload = grading.submissionPayload(form)
    assert.equal(fields.includes('vintageClassification'), productType === 'vintage_product')
    assert.ok(!fields.includes('sportsType'))
    assert.ok(grading.scoreFields.every(({ key }) => !fields.includes(key) && payload[key] === null))
    assert.equal(payload.cardCategory, 'trading_card')
    assert.equal(payload.sportsType, '')
    assert.equal(payload.groupName, '')
    assert.equal(payload.movieName, '')
    assert.equal(payload.vintageClassification, productType === 'vintage_product' ? 'A' : null)
    assert.equal(payload.merchDescription, productType === 'merch_product' ? 'Description' : null)
    assert.equal('finalGradeValue' in payload, false)
  }
})

test('missing and out-of-range scores never trigger a preview', async () => {
  let requests = 0
  const panel = setupPanel({ calculateGrade: async () => { requests++; return { data: {} } } })
  try {
    panel.state.openSubmission({ orderItemId: 80 })
    for (const value of [null, '', undefined, NaN, Infinity, 0.99, 10.04]) {
      panel.state.submissionForm.value.centeringScore = value
      await panel.state.refreshGradePreview()
    }
    assert.equal(requests, 0)
  } finally { panel.stop() }
})

test('a late preview cannot restore a grade after switching to a non-graded product', async () => {
  const pending = deferred(), panel = setupPanel({ calculateGrade: () => pending.promise })
  try {
    panel.state.openSubmission({ orderItemId: 80 })
    const preview = panel.state.refreshGradePreview()
    panel.state.submissionForm.value.productType = 'merch_product'
    await vue.nextTick()
    pending.resolve({ data: { finalGradeValue: 10, finalGradeLabel: 'Pristine 10' } })
    await preview
    assert.equal(panel.state.gradePreview.value, null)
    assert.equal(panel.state.submissionForm.value.centeringScore, null)
    panel.state.submissionForm.value.productType = 'graded_card'
    await vue.nextTick()
    assert.equal(panel.state.submissionForm.value.centeringScore, 1)
  } finally { panel.stop() }
})

test('out-of-order previews retain only the latest score calculation', async () => {
  const first = deferred(), second = deferred(); let requests = 0
  const panel = setupPanel({ calculateGrade: () => (++requests === 1 ? first : second).promise })
  try {
    panel.state.openSubmission({ orderItemId: 80 })
    const old = panel.state.refreshGradePreview()
    panel.state.submissionForm.value.centeringScore = 9.3
    const current = panel.state.refreshGradePreview()
    second.resolve({ data: { finalGradeValue: 3.08, finalGradeLabel: '8' } })
    await current
    first.resolve({ data: { finalGradeValue: 1, finalGradeLabel: '8' } })
    await old
    assert.equal(panel.state.gradePreview.value.finalGradeValue, 3.08)
    panel.state.submissionOpen.value = false
    assert.equal(panel.state.gradePreview.value, null)
  } finally { panel.stop() }
})

test('certificate and type-specific required fields fail before creating any record', async () => {
  let writes = 0
  const panel = setupPanel({ createOrderItemSubmission: async () => { writes++ } })
  try {
    const state = panel.state
    state.openSubmission({ orderItemId: 80 })
    let error
    state.submissionRules.value.certId[0].validator({}, '0123456789', value => { error = value })
    assert.equal(error.message, 'entries.certFormat')
    state.submissionForm.value.productType = 'vintage_product'
    await vue.nextTick()
    state.submissionRules.value.vintageClassification[0].validator({}, '', value => { error = value })
    assert.equal(error.message, 'entries.vintageRequired')
    state.submissionFormRef.value = { clearValidate() {}, validate: async () => { throw new Error('Invalid fields') } }
    await state.createSubmission()
    assert.equal(writes, 0)
    assert.equal(state.submissionOpen.value, true)
  } finally { panel.stop() }
})

test('generated certificate responses cannot overwrite another card dialog', async () => {
  const pending = deferred(), panel = setupPanel({ generateCertId: () => pending.promise })
  try {
    panel.state.openSubmission({ orderItemId: 80 })
    const generating = panel.state.generateCertificate()
    panel.state.openSubmission({ orderItemId: 81 })
    pending.resolve({ data: { certId: '1234567890' } })
    await generating
    assert.equal(panel.state.submissionForm.value.certId, '')
  } finally { panel.stop() }
})
