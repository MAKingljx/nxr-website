import test from 'node:test'
import assert from 'node:assert/strict'
import { partnerReturnReadinessLabel } from '../src/views/nxr/orders/partnerReturnLabels.js'

test('B return cards show shipment and eligibility without implying a manual check', () => {
  assert.equal(partnerReturnReadinessLabel({ eligible: false, itemStatus: 'grading' }), 'Not available for return')
  assert.equal(partnerReturnReadinessLabel({ eligible: true, itemStatus: 'grading' }), 'Ready to return')
  assert.equal(partnerReturnReadinessLabel({ eligible: false, itemStatus: 'return_shipped' }), 'Returning to B')
  assert.equal(partnerReturnReadinessLabel({ eligible: false, itemStatus: 'delivered' }), 'Delivered to B')
})
