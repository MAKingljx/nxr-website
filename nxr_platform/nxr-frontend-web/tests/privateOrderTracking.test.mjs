import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

const source = await readFile(new URL('../src/lib/privateOrderTracking.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
const { privateOrderNumber, privateReturnSummary, privateTrackingStatus, privateReturnStatus, privateTimelineTitle, privateTrackingDate } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
const messageSource = await readFile(new URL('../src/lib/privateOrderTrackingMessages.ts', import.meta.url), 'utf8')
const compiledMessages = ts.transpileModule(messageSource, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
const { privateTrackingMessages } = await import(`data:text/javascript;base64,${Buffer.from(compiledMessages).toString('base64')}`)
const translate = (locale) => (key, parameters = {}) => {
  const value = key.split('.').slice(1).reduce((item, part) => item?.[part], privateTrackingMessages[locale])
  assert.equal(typeof value, 'string', `Missing ${locale} translation for ${key}`)
  return value.replace(/\{(\w+)\}/g, (_, name) => String(parameters[name] ?? `{${name}}`))
}
const card = (certId, statusCode = 'return_shipped') => ({ cardName: `Card ${certId}`, certId, statusCode })
const parcel = (parcelNo, cards, statusCode = 'shipped') => ({ parcelNo, carrierName: 'Demo carrier', trackingNumber: parcelNo, statusCode, shippedAt: '2026-10-14T10:00:00', deliveredAt: null, cards })

test('new partner Orders keep their permanent short number on customer tracking', () => {
  const order = { orderNo: 'NXR261014987654', clientReference: 'NXR-000042' }
  assert.equal(privateOrderNumber(order), 'NXR-000042')
  assert.equal(order.orderNo, 'NXR261014987654')
  assert.equal(privateOrderNumber({ ...order, clientReference: 'NXR-1000000' }), 'NXR-1000000')
})

test('historical customer references do not replace the actual legacy order number', () => {
  for (const clientReference of ['AI0123456789', 'CUSTOMER-A', '', 'NXR-CLIENT', 'NXR-42']) {
    assert.equal(privateOrderNumber({ orderNo: 'NXR260917123456', clientReference }), 'NXR260917123456')
  }
})

test('one delivered parcel cannot complete a three-card order', () => {
  const summary = privateReturnSummary({ totalCardCount: 3, returnParcels: [parcel('P1', [card('0012345678', 'delivered')], 'delivered')] })
  assert.equal(summary.delivered, 1)
  assert.equal(summary.inTransit, 0)
  assert.equal(summary.notReturned, 2)
  assert.equal(summary.complete, false)
  // Even a stale overall delivered flag must not overrule the visible card evidence.
  assert.equal(privateTrackingStatus('delivered', summary), 'partial_return')
})

test('multiple partial return parcels retain separate delivered and in-transit counts', () => {
  const summary = privateReturnSummary({ totalCardCount: 4, returnParcels: [
    parcel('P1', [card('001', 'delivered')], 'delivered'),
    parcel('P2', [card('002'), card('003')]),
  ] })
  assert.deepEqual(summary, { hasParcels: true, total: 4, delivered: 1, inTransit: 2, notReturned: 1, complete: false })
  assert.equal(privateTrackingStatus('grading', summary), 'grading')
  assert.equal(privateTrackingStatus('return_shipped', summary), 'agent_returning')
})

test('completion requires every order card across all parcels delivered to the agent', () => {
  const summary = privateReturnSummary({ totalCardCount: 3, returnParcels: [
    parcel('P1', [card('001', 'delivered')], 'delivered'),
    parcel('P2', [card('002', 'delivered'), card('003', 'delivered')], 'delivered'),
  ] })
  assert.equal(summary.complete, true)
  assert.equal(summary.delivered, 3)
  assert.equal(summary.notReturned, 0)
  assert.equal(privateTrackingStatus('delivered', summary), 'agent_delivered')
})

test('legacy shipments and inbound receipts do not count as new card deliveries', () => {
  const summary = privateReturnSummary({ totalCardCount: 3,
    shipments: [{ directionCode: 'inbound', statusCode: 'delivered' }, { directionCode: 'outbound', statusCode: 'delivered' }],
    returnParcels: [parcel('P1', [card('001')])],
  })
  assert.equal(summary.delivered, 0)
  assert.equal(summary.inTransit, 1)
  assert.equal(summary.notReturned, 2)
  assert.equal(summary.complete, false)
})

test('older payloads preserve legacy delivery status without fabricating parcel counts', () => {
  const summary = privateReturnSummary({ cardCount: 2, statusCode: 'delivered' })
  assert.equal(summary.hasParcels, false)
  assert.equal(summary.delivered, 0)
  assert.equal(summary.complete, false)
  assert.equal(privateTrackingStatus('delivered', summary), 'agent_delivered')
  assert.equal(privateReturnSummary({ totalCardCount: 2, returnParcels: [] }).hasParcels, false)
})

test('duplicate, missing or contradictory card identities cannot assert full delivery', () => {
  const duplicate = privateReturnSummary({ totalCardCount: 2, returnParcels: [
    parcel('P1', [card('VRA001', 'delivered')], 'delivered'),
    parcel('P2', [card('vra001', 'delivered')], 'delivered'),
  ] })
  assert.equal(duplicate.delivered, 1)
  assert.equal(duplicate.complete, false)
  const missing = privateReturnSummary({ totalCardCount: 1, returnParcels: [parcel('P1', [card(null, 'delivered')], 'delivered')] })
  assert.equal(missing.complete, false)
  const contradictory = privateReturnSummary({ totalCardCount: 1, returnParcels: [parcel('P1', [card('001', 'delivered')], 'shipped')] })
  assert.equal(contradictory.delivered, 0)
  assert.equal(contradictory.complete, false)
})

test('unknown or zero total never creates an empty completed order', () => {
  for (const total of [undefined, 0, -1, Number.NaN]) {
    const summary = privateReturnSummary({ totalCardCount: total, returnParcels: [] })
    assert.equal(summary.total, 0)
    assert.equal(summary.complete, false)
  }
})

test('explicit cancellation and payment holds remain visible independently of delivery', () => {
  const summary = privateReturnSummary({ totalCardCount: 1, returnParcels: [parcel('P1', [card('001', 'delivered')], 'delivered')] })
  assert.equal(summary.complete, true)
  assert.equal(privateTrackingStatus('cancelled', summary), 'cancelled')
  assert.equal(privateTrackingStatus('payment_exception', summary), 'payment_exception')
})

test('private tracking covers every supported locale with identical message keys', async () => {
  const i18nSource = await readFile(new URL('../src/i18n.ts', import.meta.url), 'utf8')
  const supported = [...i18nSource.matchAll(/\{ code: '([^']+)', label:/g)].map((match) => match[1])
  assert.deepEqual(Object.keys(privateTrackingMessages).sort(), supported.sort())
  const keys = (object, prefix = '') => Object.entries(object).flatMap(([key, value]) => typeof value === 'string' ? [`${prefix}${key}`] : keys(value, `${prefix}${key}.`))
  assert.deepEqual(keys(privateTrackingMessages.en), keys(privateTrackingMessages['zh-CN']))
})

test('system timeline titles switch language without changing parcel delivery scope', () => {
  const cases = [
    ['application_submitted', 'Application submitted', '申请已提交'],
    ['batch_inbound_shipped', 'Agent parcel shipped to NXR', '代理包裹已寄往 NXR'],
    ['batch_return_shipped', 'Agent return parcel shipped', '寄回代理的包裹已寄出'],
    ['batch_delivered', 'Agent return parcel delivered', '回寄包裹已送达代理'],
    ['partner_return_shipped', 'Cards shipped to partner', '卡片已寄回代理'],
    ['partner_return_delivered', 'Cards delivered to partner', '卡片已送达代理'],
    ['shipment_in_transit', 'Shipment in transit', '包裹运输中'],
    ['quality_passed', 'Final quality check passed', '最终质检已通过'],
  ]
  for (const [eventCode, title, chinese] of cases) {
    const event = Object.freeze({ eventCode, title, statusCode: 'grading' })
    assert.equal(privateTimelineTitle(event, translate('en')), title)
    assert.equal(privateTimelineTitle(event, translate('zh-CN')), chinese)
    assert.equal(event.title, title)
  }
  assert.notEqual(privateTimelineTitle({ eventCode: 'partner_return_delivered', title: 'Cards delivered to partner' }, translate('zh-CN')), privateTrackingMessages['zh-CN'].allDelivered)
})

test('status changes retain their event meaning instead of taking the current order label', () => {
  const cases = [
    ['awaiting_inbound', 'Payment confirmed', '付款已确认'],
    ['completed', 'Grading completed', '评级已完成'],
    ['quality_hold', 'Quality rework required', '质量复核需返工'],
    ['delivered', 'Return shipment delivered', '回寄包裹已送达'],
  ]
  for (const [statusCode, title, chinese] of cases) {
    const event = { eventCode: 'status_changed', statusCode, title }
    assert.equal(privateTimelineTitle(event, translate('en')), title)
    assert.equal(privateTimelineTitle(event, translate('zh-CN')), chinese)
  }
  assert.equal(privateTimelineTitle({ eventCode: 'work_task_completed', title: 'Manual grading review completed' }, translate('zh-CN')), '人工评级复核：已完成')
  assert.equal(privateTimelineTitle({ eventCode: 'work_task_failed', title: 'Encapsulation failed' }, translate('en')), 'Encapsulation failed')
})

test('unknown event codes, custom titles and manual notes survive verbatim in every language', () => {
  const cases = [
    { eventCode: 'new_future_event', title: 'Cards delivered to partner' },
    { title: 'Cards delivered to partner' },
    { eventCode: 'partner_return_delivered', title: '已交给王先生 — parcel A/2' },
    { eventCode: 'status_note', statusCode: 'grading', title: 'Grading in progress' },
    { eventCode: 'status_changed', statusCode: 'grading', title: '  请联系 Alice：卡名「喷火龙」需复核  ' },
    { eventCode: 'status_changed', statusCode: 'future_status', title: 'Grading completed' },
    { eventCode: 'work_task_completed', title: 'Manual grading review completed — check with DHL' },
    { eventCode: 'shipment_in_transit', title: 'DHL：已到 Paris / 法国' },
    { eventCode: 'constructor', title: 'Unknown title' },
  ]
  for (const locale of Object.keys(privateTrackingMessages)) {
    for (const event of cases) assert.equal(privateTimelineTitle(Object.freeze(event), translate(locale)), event.title)
  }
})

test('card and parcel return statuses localize consistently while unknown status codes stay intact', () => {
  for (const locale of Object.keys(privateTrackingMessages)) {
    const t = translate(locale)
    assert.equal(t(`tracking.statuses.${privateReturnStatus('delivered')}`), locale === 'en' ? 'Delivered to agent' : '已送达代理')
    for (const code of ['shipped', 'return_shipped']) {
      assert.equal(t(`tracking.statuses.${privateReturnStatus(code)}`), locale === 'en' ? 'On the way to agent' : '正在寄回代理')
    }
    for (const code of ['expired', 'label_created', 'picked_up', 'customs', 'exception', 'in_transit', 'out_for_delivery']) {
      assert.ok(t(`tracking.statuses.${privateReturnStatus(code)}`))
    }
  }
  assert.equal(privateReturnStatus('future_status_with_detail'), 'future_status_with_detail')
})

test('return counts and fixed progress copy use the selected language and preserve actual totals', () => {
  const en = translate('en'), zh = translate('zh-CN')
  assert.equal(en('tracking.deliveredCount', { count: 1, total: 3 }), '1 of 3 cards delivered to agent')
  assert.equal(zh('tracking.deliveredCount', { count: 1, total: 3 }), '3 张卡片中已有 1 张送达代理')
  assert.equal(en('tracking.inTransitCount', { count: 1 }), '1 cards on the way to agent')
  assert.equal(zh('tracking.remainingCount', { count: 2 }), '2 张尚未回寄')
  assert.match(en('tracking.returnEndpoint'), /every card/)
  assert.match(zh('tracking.returnEndpoint'), /全部卡片送达代理/)
})

test('tracking dates follow the current language without changing the timestamp', () => {
  const value = new Date('2026-10-15T08:09:10Z'), timestamp = value.getTime()
  const options = { timeZone: 'Asia/Shanghai' }
  const en = privateTrackingDate(value, 'en', options), zh = privateTrackingDate(value, 'zh-CN', options)
  assert.equal(en, value.toLocaleString('en', options))
  assert.equal(zh, value.toLocaleString('zh-CN', options))
  assert.notEqual(en, zh)
  assert.equal(privateTrackingDate('2026-10-15T08:09:10Z', 'zh-CN', options), zh)
  assert.equal(value.getTime(), timestamp)
  for (const invalid of [null, undefined, '', 'invalid-date', new Date(Number.NaN)]) {
    assert.equal(privateTrackingDate(invalid, 'zh-CN'), '—')
  }
})
