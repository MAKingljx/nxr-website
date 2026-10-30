import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFileSync } from 'node:fs'
import { parse } from '@vue/compiler-sfc'
import { computed, ref } from 'vue'
import { buildSubmitDecision, canReviewSubmit, joinSubmitReviewOrders, submitDecisionReason } from '../src/views/nxr/orders/submitAdmission.js'

const orders = [{ orderId: 5, orderNo: 'A', statusCode: 'admission_review' }, { orderId: 9, orderNo: 'B', statusCode: 'admission_review' }]
const review = { eligible: true, orders: [{ orderId: 9, admissionStatus: 'pending_review', admissionRevision: 4 }, { orderId: 5, admissionStatus: 'pending_review', admissionRevision: 2 }] }

test('review matches every order by ID and preserves each revision', () => {
  assert.equal(canReviewSubmit(orders, review), true)
  assert.deepEqual(joinSubmitReviewOrders(orders, review.orders).map(row => row.admissionRevision), [2, 4])
  assert.deepEqual(buildSubmitDecision(review, 'approve', ' note '), { decision: 'approve', note: 'note', orders: [{ orderId: 9, expectedRevision: 4 }, { orderId: 5, expectedRevision: 2 }] })
})

test('partial, duplicate, outdated or ineligible lists cannot be reviewed', () => {
  assert.equal(canReviewSubmit(orders, { ...review, eligible: false }), false)
  assert.equal(canReviewSubmit(orders, { ...review, orders: review.orders.slice(0, 1) }), false)
  assert.equal(canReviewSubmit([orders[0], orders[0]], review), false)
  assert.equal(canReviewSubmit(orders, { ...review, orders: [review.orders[0], review.orders[0]] }), false)
  assert.equal(canReviewSubmit(orders, { ...review, orders: [{ ...review.orders[0], admissionStatus: 'needs_information' }, review.orders[1]] }), false)
  assert.equal(canReviewSubmit([{ ...orders[0], statusCode: 'awaiting_inbound' }, orders[1]], review), false)
  assert.equal(canReviewSubmit([], { eligible: true, orders: [] }), false)
})

test('only nonempty rejection or information-request reasons are displayed', () => {
  for (const admissionStatus of ['rejected', 'needs_information']) {
    assert.equal(submitDecisionReason({ admissionStatus, decisionNote: '  Original note\n原文  ' }), '  Original note\n原文  ')
    assert.equal(submitDecisionReason({ admissionStatus, decisionNote: '   ' }), '')
    assert.equal(submitDecisionReason({ admissionStatus, decisionNote: null }), '')
  }
  assert.equal(submitDecisionReason({ admissionStatus: 'approved', decisionNote: '' }), '')
  assert.equal(submitDecisionReason({ admissionStatus: 'approved', decisionNote: 'old note' }), '')
  assert.equal(submitDecisionReason({ admissionStatus: 'pending_review', decisionNote: 'old note' }), '')
})

async function reviewFixture({ cancel = false, reason = 'Reason for all', confirmCancel = false } = {}) {
  const source = readFileSync(new URL('../src/views/nxr/orders/components/PartnerSubmitAdmissionPanel.vue', import.meta.url), 'utf8')
  const script = parse(source).descriptor.scriptSetup.content.replace(/^import .*\n/gm, '')
  const calls = [], events = []
  const context = {
    computed, ref, watch: () => {}, onBeforeUnmount: () => {}, defineProps: () => ({ batchId: 12, orders }),
    defineEmits: () => (...args) => events.push(args), tx: value => value, localizeBackendMessage: value => value,
    getCurrentInstance: () => ({ proxy: { $modal: { msgWarning: () => {}, msgSuccess: () => {}, confirm: async () => { calls.push('confirm'); if (confirmCancel) throw new Error('cancel') } } } }),
    ElMessageBox: { prompt: async (text, title, options) => { calls.push(['prompt', title, options]); if (cancel) throw new Error('cancel'); return { value: reason } } },
    getPartnerSubmitAdmission: async () => ({ data: review }),
    decidePartnerSubmitAdmission: async (id, data) => calls.push([id, JSON.parse(JSON.stringify(data))]),
    buildSubmitDecision, canReviewSubmit, joinSubmitReviewOrders, submitDecisionReason
  }
  vm.runInNewContext(script + '\nglobalThis.state = { load, decide };', context)
  await context.state.load()
  return { context, calls, events }
}

test('approval confirms and posts one complete versioned batch decision without a note', async () => {
  const { context, calls, events } = await reviewFixture()
  await context.state.decide('approve')
  assert.equal(calls[0], 'confirm')
  assert.deepEqual(calls[1], [12, { decision: 'approve', note: '', orders: [{ orderId: 9, expectedRevision: 4 }, { orderId: 5, expectedRevision: 2 }] }])
  assert.deepEqual(events, [['changed']])
})

test('rejection and information requests prompt for a nonblank reason for the entire batch', async () => {
  for (const decision of ['reject', 'request_information']) {
    const { context, calls } = await reviewFixture({ reason: '  Check the photos  ' })
    await context.state.decide(decision)
    assert.equal(calls[0][0], 'prompt')
    assert.equal(calls[0][2].inputValidator('   '), 'Enter a review note')
    assert.equal(calls[0][2].inputValidator('reason'), true)
    assert.equal(calls[1][1].note, 'Check the photos')
    assert.equal(calls[1][1].decision, decision)
    assert.equal(calls[1][1].orders.length, 2)
  }
})

test('cancelled confirmation or a blank reason sends no decision', async () => {
  for (const [decision, settings] of [['approve', { confirmCancel: true }], ['reject', { cancel: true }], ['request_information', { reason: '   ' }]]) {
    const { context, calls, events } = await reviewFixture(settings)
    await context.state.decide(decision)
    assert.equal(calls.length, 1)
    assert.deepEqual(events, [])
  }
})
