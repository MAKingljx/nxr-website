import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFileSync } from 'node:fs'
import { parse } from '@vue/compiler-sfc'

test('internal tasks and whole-order quality check remain available without verified card-based returns', () => {
  const source = readFileSync(new URL('../src/views/nxr/orders/index.vue', import.meta.url), 'utf8')
  const template = parse(source).descriptor.template.ast
  const nodes = []
  const visit = node => { if (node.type === 1) nodes.push(node); for (const child of node.children || []) visit(child) }
  visit(template)
  const scope = nodes.find(node => node.tag === 'template' && node.props.some(prop => prop.name === 'if' && prop.exp?.content === 'detail.partnerCardReturnEligible !== true') && node.loc.source.includes('Internal Tasks & Quality Check'))
  assert.ok(scope, 'task and quality controls must share the explicit card-based-return guard')
  assert.match(scope.loc.source, /@click="createTask"/)
  assert.match(scope.loc.source, /operations\.workTasks/)
  assert.match(scope.loc.source, /@click="saveQualityCheck"/)
  assert.doesNotMatch(scope.loc.source, /<order-workbench-panel/)
  const condition = scope.props.find(prop => prop.name === 'if').exp.content
  const show = detail => vm.runInNewContext(condition, { detail })
  assert.equal(show({ orderChannel: 'partner', partnerCardReturnEligible: true }), false)
  assert.equal(show({ orderChannel: 'partner', merchantBatch: { batchId: 1 }, partnerCardReturnEligible: false }), true)
  assert.equal(show({ orderChannel: 'partner', merchantBatch: { batchId: 1 } }), true)
  assert.equal(show({ orderChannel: 'individual', partnerCardReturnEligible: false }), true)
})

test('B order workbench hides scan guidance and identity scan controls while keeping legacy behavior', () => {
  const source = readFileSync(new URL('../src/views/nxr/orders/components/OrderWorkbenchPanel.vue', import.meta.url), 'utf8')
  const template = parse(source).descriptor.template.ast, nodes = []
  const visit = node => { if (node.type === 1) nodes.push(node); for (const child of node.children || []) visit(child) }
  visit(template)
  const guard = node => node.props.some(prop => prop.name === 'if' && prop.exp?.content?.includes('!orderReturnMode'))
  const heading = nodes.find(node => node.tag === 'div' && node.loc.source.includes('Lock one order, then scan every physical card'))
  const identity = nodes.find(node => node.tag === 'NxrCardIdentityPanel')
  const scan = nodes.find(node => node.tag === 'template' && node.loc.source.includes('@submit.prevent="scan"'))
  assert.ok(heading && guard(heading))
  assert.ok(identity && guard(identity))
  assert.ok(scan && guard(scan))
  assert.match(source, /v-if="orderReturnMode \|\| \(ownsActiveSession && scope\.row\.intakeScannedAt\)"/)
})

test('B order receipt uses non-scan copy while the legacy receipt keeps its existing text', () => {
  const source = readFileSync(new URL('../src/views/nxr/orders/index.vue', import.meta.url), 'utf8')
  const parcel = readFileSync(new URL('../src/views/nxr/agent-workbench/components/AgentPartnerReturns.vue', import.meta.url), 'utf8')
  assert.match(source, /detail\.partnerCardReturnEligible === true \? 'Confirm parcel receipt' : 'Confirm Intake'/)
  assert.match(source, /orderReturnMode \? 'Order receipt confirmed\.' : 'Warehouse intake and card count recorded'/)
  assert.match(parcel, /data-testid="agent-confirm-partner-parcel"[^>]*>\{\{ \$tx\('Confirm parcel receipt'\) \}\}/)
})
