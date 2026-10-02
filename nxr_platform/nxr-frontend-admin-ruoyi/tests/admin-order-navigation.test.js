import assert from 'node:assert/strict'
import test from 'node:test'
import vm from 'node:vm'
import { readFileSync } from 'node:fs'
import { orderDetailSection, orderManualStatuses } from '../src/views/nxr/orders/orderPresentation.js'

const source = readFileSync(new URL('../src/views/nxr/orders/index.vue', import.meta.url), 'utf8')
const loadOrdersSource = source.slice(source.indexOf('function loadOrders('), source.indexOf('function resetQuery('))

function paginationContext(page = 2) {
  const calls = []
  const context = { queryParams: { page, pageSize: 10 }, loading: { value: false }, rows: { value: [] }, total: { value: 0 }, listGradingOrders: async query => {
    calls.push({ ...query })
    return { data: { items: [{ id: query.page }], total: 16, page: query.page, pageSize: query.pageSize } }
  } }
  vm.runInNewContext(loadOrdersSource, context)
  return { context, calls }
}

test('pagination events preserve the selected order page and backend result', async () => {
  const { context, calls } = paginationContext()
  await context.loadOrders({ page: 2, limit: 10 })
  assert.equal(calls[0].page, 2)
  assert.equal(context.queryParams.page, 2)
  assert.equal(context.rows.value[0].id, 2)
  assert.equal(context.total.value, 16)
  assert.equal(context.loading.value, false)
})

test('search alone explicitly resets page, while refresh stays on the current page', async () => {
  const { context, calls } = paginationContext(3)
  await context.loadOrders()
  assert.equal(calls[0].page, 3)
  await context.loadOrders(true)
  assert.equal(calls[1].page, 1)
})

test('backend failures release order loading state', async () => {
  const { context } = paginationContext()
  context.listGradingOrders = async () => { throw new Error('Unavailable') }
  await assert.rejects(context.loadOrders(), /Unavailable/)
  assert.equal(context.loading.value, false)
})

test('dashboard links select the requested order section and reject unknown sections', () => {
  assert.equal(orderDetailSection({ statusCode: 'received' }, 'support'), 'support')
  assert.equal(orderDetailSection({ statusCode: 'received' }, 'finance'), 'finance')
  assert.equal(orderDetailSection({ statusCode: 'received' }, ['support']), 'warehouse')
  assert.equal(orderDetailSection({ statusCode: 'grading' }, 'invalid'), 'cards')
  assert.equal(orderDetailSection({ statusCode: 'payment_exception' }), 'finance')
  assert.equal(orderDetailSection({ statusCode: 'admission_review' }), 'admission')
  assert.equal(orderDetailSection({ statusCode: 'completed' }), 'shipping')
  assert.equal(orderDetailSection({ statusCode: 'delivered' }), 'timeline')
})

test('master-batch orders keep grading transitions and exclude independent logistics', () => {
  const statuses = ['inbound_shipped', 'received', 'grading', 'completed', 'return_shipped', 'delivered', 'cancelled'].map(value => ({ value }))
  assert.deepEqual(orderManualStatuses(statuses, { batchId: 1 }).map(option => option.value), ['received', 'grading', 'completed', 'cancelled'])
  assert.equal(orderManualStatuses(statuses, null), statuses)
  assert.equal(statuses.length, 7)
})
