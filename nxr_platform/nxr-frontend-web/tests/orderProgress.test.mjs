import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

const source = await readFile(new URL('../src/lib/orderProgress.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
const { orderProgress, formatMoney, orderDisplayStatus } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
const base = { statusCode: 'inbound_shipped', createdAt: '2026-09-08T08:00:00', timeline: [], payments: [], shipments: [] }

test('inbound delivery never marks the return delivery milestone reached', () => {
  const result = orderProgress({ ...base, shipments: [{ directionCode: 'inbound', shippedAt: '2026-09-08T09:00:00', deliveredAt: '2026-09-09T10:00:00' }], timeline: [{ eventCode: 'shipment_delivered', statusCode: 'delivered', createdAt: '2026-09-09T10:00:00' }] })
  assert.equal(result[7].reachedAt, null)
  assert.equal(result[7].completed, false)
  assert.equal(result[2].active, true)
})
test('completed grading is awaiting return shipment, not delivered', () => {
  const result = orderProgress({ ...base, statusCode: 'completed' })
  assert.equal(result[5].active, true)
  assert.equal(result[6].completed, false)
  assert.equal(result[7].reachedAt, null)
})
test('cancelled orders preserve only documented milestones', () => {
  const result = orderProgress({ ...base, statusCode: 'cancelled' })
  assert.equal(result.filter(step => step.active).length, 0)
  assert.equal(result[0].completed, true)
  assert.equal(result[1].completed, false)
})
test('return delivery completes the final step and payment holds preserve history', () => {
  const shipped = { directionCode: 'outbound', shippedAt: '2026-09-12T09:00:00', deliveredAt: '2026-09-13T10:00:00' }
  const result = orderProgress({ ...base, statusCode: 'delivered', shipments: [shipped] })
  assert.equal(result.every(step => step.completed && !step.active), true)
  assert.equal(result[7].reachedAt, shipped.deliveredAt)
  const held = orderProgress({ ...base, statusCode: 'payment_exception' })
  assert.equal(held.some(step => step.active), false)
  assert.equal(held[0].completed, true)
  assert.equal(held[7].completed, false)
})
test('milestone time uses earliest evidence and never fabricates skipped timestamps', () => {
  const result = orderProgress({ ...base, statusCode: 'review', timeline: [
    { eventCode: 'review_retry', statusCode: 'review', createdAt: '2026-09-12T10:00:00' },
    { eventCode: 'review_started', statusCode: 'review', createdAt: '2026-09-11T10:00:00' },
  ] })
  assert.equal(result[5].reachedAt, '2026-09-11T10:00:00')
  assert.equal(result[4].reachedAt, null)
})
test('JPY uses zero decimals while CNY preserves cents', () => {
  assert.match(formatMoney(1234, 'JPY'), /1,234$/)
  assert.match(formatMoney(12.5, 'CNY'), /12\.50$/)
})

test('cancellation and payment expiry take precedence over old admission metadata', () => {
  assert.equal(orderDisplayStatus('cancelled', 'pending_review'), 'cancelled')
  assert.equal(orderDisplayStatus('payment_expired', 'approved'), 'payment_expired')
  assert.equal(orderDisplayStatus('admission_review', 'needs_information'), 'needs_information')
})

test('enterprise points use a unit label instead of a currency formatter', () => {
  assert.equal(formatMoney(1234.5, 'PTS'), '1,234.50 PTS')
  assert.equal(formatMoney(0, 'PTS'), '0.00 PTS')
})

test('large exact-decimal enterprise balances retain their cents', () => {
  assert.equal(formatMoney('99999999999999.99', 'PTS'), '99,999,999,999,999.99 PTS')
  assert.equal(formatMoney('0.01', 'PTS'), '0.01 PTS')
})

test('legacy USD and CNY preserve cents at the DECIMAL(18,2) limit', () => {
  for (const currency of ['USD', 'CNY']) {
    assert.match(formatMoney('9999999999999999.99', currency), /9,999,999,999,999,999\.99$/)
    assert.match(formatMoney('-9999999999999999.98', currency), /-.*9,999,999,999,999,999\.98$/)
  }
})
test('exact JPY uses zero decimals and integer rounding at large amounts', () => {
  assert.match(formatMoney('9999999999999999.00', 'JPY'), /9,999,999,999,999,999$/)
  assert.match(formatMoney('9999999999999999.50', 'JPY'), /10,000,000,000,000,000$/)
})
test('normal numbers retain Intl currency formatting and decimal signs', () => {
  for (const currency of ['USD', 'CNY', 'JPY']) for (const amount of [0, 12.5, -0.01, 1234.567]) {
    const expected = new Intl.NumberFormat('en', { style: 'currency', currency, currencyDisplay: 'code' }).format(amount)
    assert.equal(formatMoney(amount, currency), expected)
    assert.equal(formatMoney(String(amount), currency), expected)
  }
  assert.match(formatMoney('999.995', 'USD'), /1,000\.00$/)
})
