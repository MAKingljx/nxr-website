import assert from 'node:assert/strict'
import test from 'node:test'
import { addressLabel, canCompleteExistingIntake, displayedCardStatus, draftFingerprint, orderReturnProgress, orderSelection, submissionOrderIds, validateOrderDraft } from '../src/views/nxr/agent-workbench/lib/workbenchOrders.js'
const image = name => ({ file: { name, size: 128, lastModified: 123 }, id: null, url: 'blob:preview' })
const card = () => ({ localId: 'draft-card', cardName: 'Charizard', brandName: 'Pokemon', setName: 'Base Set', officialCardNumber: '4/102', languageCode: 'EN', productType: 'graded', category: 'TCG', front: image('front.jpg'), back: image('back.jpg') })
const draft = () => ({ clientName: 'Demo customer', phone: '+1 555 0100', email: '', notes: '', customerAddress: { addressLine1: '12 Demo Street', addressLine2: '', city: 'Paris', region: '', postalCode: '75001', country: 'FR' }, cards: [card()] })
test('contact, full card metadata and both photographed sides are mandatory', () => {
  const value = draft()
  assert.equal(validateOrderDraft(value, 200), '')
  value.phone = ''
  assert.equal(validateOrderDraft(value, 200), 'Enter a phone number or email address.')
  value.email = 'demo@example.test'
  value.cards[0].back.file = null
  assert.equal(validateOrderDraft(value, 200), 'Photograph and attach both sides of every card.')
  value.cards[0].back.id = 123
  assert.equal(validateOrderDraft(value, 200), '')
  value.cards[0].setName = ''
  assert.equal(validateOrderDraft(value, 200), 'Complete the required card fields.')
  assert.equal(validateOrderDraft({ ...draft(), cards: [card(), card()] }, 1), 'Check the card quantity.')
})
test('partial image uploads keep the retry key fingerprint while changed input starts a new attempt', () => {
  const value = draft(), before = draftFingerprint(value)
  value.cards[0].front.id = 22
  assert.deepEqual(draftFingerprint(value), before)
  value.cards[0].front.url = 'blob:another-preview'
  assert.deepEqual(draftFingerprint(value), before)
  value.cards[0].front.file.lastModified++
  assert.notDeepEqual(draftFingerprint(value), before)
})
test('return progress stays hidden until at least one card starts returning', () => {
  assert.equal(orderReturnProgress({ cardCount: 3 }), null)
  assert.equal(orderReturnProgress({ cardCount: 3, deliveredCardCount: null, returnShippedCardCount: null }), null)
  assert.equal(orderReturnProgress({ cardCount: 3, deliveredCardCount: 0, returnShippedCardCount: 0 }), null)
  assert.deepEqual(orderReturnProgress({ cardCount: 3, deliveredCardCount: 1, returnShippedCardCount: 1 }), { total: 3, delivered: 1, shipped: 1, complete: false })
  assert.deepEqual(orderReturnProgress({ cardCount: 3, deliveredCardCount: 0, returnShippedCardCount: 1 }), { total: 3, delivered: 0, shipped: 1, complete: false })
  assert.equal(orderReturnProgress({ cardCount: 3, deliveredCardCount: 3, returnShippedCardCount: 0 }).complete, true)
  assert.equal(orderReturnProgress({ cardCount: 0, deliveredCardCount: 0, returnShippedCardCount: 0 }), null)
})
test('only complete unsubmitted orders can enter outbound Submit selection', () => {
  const ready = { id: 1, canSubmit: true }
  assert.deepEqual(orderSelection({ 1: ready, 2: { id: 2, canSubmit: false } }), [ready])
})


test('only an unfinished legacy intake can reopen receipt and card checks', () => {
  for (const intakeStatusCode of ['expected', 'received', 'exception']) assert.equal(canCompleteExistingIntake({ legacy: true, intakeStatusCode, gradingOrderId: null }), true)
  for (const intakeStatusCode of ['ready', 'submitted']) assert.equal(canCompleteExistingIntake({ legacy: true, intakeStatusCode, gradingOrderId: null }), false)
  assert.equal(canCompleteExistingIntake({ legacy: false, intakeStatusCode: 'expected' }), false)
  assert.equal(canCompleteExistingIntake({ legacy: true, intakeStatusCode: 'received', gradingOrderId: 100 }), false)
})

test('new order requires a customer address and address changes invalidate the retry key', () => {
  const value = draft(), before = draftFingerprint(value)
  value.customerAddress.addressLine1 = ''
  assert.equal(validateOrderDraft(value, 200), 'Complete the customer address.')
  assert.notDeepEqual(draftFingerprint(value), before)
  assert.equal(addressLabel(null), '')
  assert.equal(addressLabel(draft().customerAddress), 'FR · Paris · 12 Demo Street · 75001')
})
test('Submit selections preserve valid IDs and reject malformed or excessive route inputs', () => {
  assert.deepEqual(submissionOrderIds('7,3,7'), [3,7])
  assert.deepEqual(submissionOrderIds([9,1]), [1,9])
  assert.deepEqual(submissionOrderIds(undefined), [])
  for (const value of ['-1','0','2.5','1,,2','1e3','9007199254740993',Array(101).fill(1)]) assert.throws(() => submissionOrderIds(value))
})

test('cards in an already shipped inbound parcel display the transit state', () => {
  assert.equal(displayedCardStatus('inbound_shipped', 'awaiting_inbound'), 'inbound_shipped')
  assert.equal(displayedCardStatus('inbound_shipped', 'grading'), 'grading')
  assert.equal(displayedCardStatus('awaiting_inbound', 'awaiting_inbound'), 'awaiting_inbound')
})
