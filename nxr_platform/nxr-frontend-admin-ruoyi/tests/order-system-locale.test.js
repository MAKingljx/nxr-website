import assert from 'node:assert/strict'
import test from 'node:test'
import vm from 'node:vm'
import { readFileSync } from 'node:fs'
import { computed, effectScope, reactive, ref, watch, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { parse } from '@vue/compiler-sfc'
import en from '../src/i18n/messages/en.js'
import zhCN from '../src/i18n/messages/zh-CN.js'
import baseLiteralZhCN from '../src/i18n/literal-zh-CN.js'
import workspaceZhCN from '../src/i18n/partner-workspace-zh-CN.js'
import partnerZhCN from '../src/i18n/partner-management-zh-CN.js'
import overviewZhCN from '../src/i18n/workspace-overview-zh-CN.js'
import enterpriseWalletZhCN from '../src/i18n/enterprise-wallet-zh-CN.js'
import cardOperatorsZhCN from '../src/i18n/card-operators-zh-CN.js'
import partnerReturnsZhCN from '../src/i18n/partner-returns-zh-CN.js'
import { orderSystemZhCN, localizeKnownOrderText, localizeDefaultOrderText, localizeOrderEventDetail } from '../src/i18n/order-system-text.js'

const orderBackendServices = ['customer/AgentOrderService', 'customer/AgentWorkbenchService', 'customer/MerchantBatchService', 'customer/PartnerReturnService', 'customer/OrderWorkbenchService', 'customer/OrderFulfillmentService', 'admission/OrderAdmissionService', 'customer/CustomerOrderPhotoService', 'commerce/OrderAccessScopeService', 'commerce/ScopedOrderSubmissionService', 'customer/OrderCardIdentityService', 'customer/AgentOrderCancellationService']
const backendRoot = new URL('../../nxr-backend-ruoyi/ruoyi-nxr/src/main/java/com/nxr/platform/', import.meta.url)
const javaLiteral = '"(?:\\\\.|[^"\\\\])*"'
function javaArguments(source, start) {
  const args = []
  let depth = 1, quoted = false, escaped = false, from = start
  for (let index = start; index < source.length; index++) {
    const character = source[index]
    if (quoted) {
      if (escaped) escaped = false
      else if (character === '\\') escaped = true
      else if (character === '"') quoted = false
      continue
    }
    if (character === '"') quoted = true
    else if (character === '(') depth++
    else if (character === ')') {
      if (--depth === 0) { args.push(source.slice(from, index).trim()); return args }
    } else if (character === ',' && depth === 1) { args.push(source.slice(from, index).trim()); from = index + 1 }
  }
  return []
}
function staticJavaStrings(expression = '') {
  if (new RegExp(`^(?:${javaLiteral}\\s*\\+\\s*)*${javaLiteral}$`).test(expression)) {
    return [[...expression.matchAll(/"((?:\\.|[^"\\])*)"/g)].map(match => JSON.parse('"' + match[1] + '"')).join('')]
  }
  // A static message in either conditional branch remains part of the exception contract.
  const conditional = expression.match(new RegExp(`^[\\s\\S]+\\?\\s*(${javaLiteral})\\s*:\\s*(${javaLiteral})$`))
  return conditional ? conditional.slice(1).map(value => JSON.parse(value)) : []
}
function fixedBackendErrors(source) {
  const messages = new Set()
  for (const match of source.matchAll(/(?:new\s+ResponseStatusException|\b(?:badRequest|bad|conflict|forbidden|notFound))\s*\(/g)) {
    const args = javaArguments(source, match.index + match[0].length)
    for (const message of staticJavaStrings(args[match[0].startsWith('new') ? 1 : 0])) messages.add(message)
  }
  return messages
}

const changedLabelMessages = [
  'The grading result changed after label export; export the current labels before scanning or shipping',
  'The grading result changed after label scanning; re-export and rescan this label',
  'A grading result changed after label scanning; re-export and rescan the affected label'
]

function localeFixture(environment = 'java-stage', savedLocale = 'en') {
  const source = readFileSync(new URL('../src/i18n/index.js', import.meta.url), 'utf8')
    .replace(/^import .*\n/gm, '').replace(/\bexport /g, '').replaceAll('import.meta.env.VITE_APP_ENV', JSON.stringify(environment))
  const storage = new Map([['nxr-admin-locale', savedLocale]])
  const context = { createI18n, en, zhCN, baseLiteralZhCN, workspaceZhCN, partnerZhCN, overviewZhCN, enterpriseWalletZhCN, cardOperatorsZhCN, partnerReturnsZhCN, localizeKnownOrderText, localizeDefaultOrderText, translateOrderEventDetail: localizeOrderEventDetail,
    window: { localStorage: { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value) } }, document: { documentElement: {} } }
  vm.runInNewContext(source + '\nglobalThis.api = {i18n,tx,localizeBackendMessage,localizeOrderTerms,localizeOrderEventDetail,allowLocaleSelection};', context)
  return context.api
}

test('recognized system titles and errors translate both directions without changing unknown content', () => {
  for (const [english, chinese] of Object.entries(orderSystemZhCN)) {
    assert.equal(localizeKnownOrderText(english, 'zh-CN'), chinese)
    assert.equal(localizeKnownOrderText(chinese, 'en'), english)
  }
  for (const value of [null, undefined, '', '未知的客户备注 / Custom note', 'Please keep inventory codes as printed.', '  客户原文\nSecond line  ', 'Complete new-provider check']) {
    assert.equal(localizeKnownOrderText(value, 'en'), value)
    assert.equal(localizeKnownOrderText(value, 'zh-CN'), value)
  }
  const parsed = fixedBackendErrors(`throw new ResponseStatusException(\n HttpStatus.CONFLICT,\n "First " + "fixed message", cause);\nthrow bad("Second message");\nthrow new ResponseStatusException(HttpStatus.CONFLICT, printed ? "Printed version changed" : "Print first");`)
  assert.deepEqual([...parsed], ['First fixed message', 'Second message', 'Printed version changed', 'Print first'])
  const api = localeFixture('java-stage', 'zh-CN'), covered = new Set()
  for (const name of orderBackendServices) {
    const source = readFileSync(new URL(name + '.java', backendRoot), 'utf8')
    for (const message of fixedBackendErrors(source)) {
      covered.add(message)
      assert.notEqual(api.localizeBackendMessage(message), message, `${name}: missing ${message}`)
    }
    for (const match of source.matchAll(/\b(requireText|normalizeCode|normalizeMoney|optional|required|require)\s*\(/g)) {
      const [, labelExpression] = javaArguments(source, match.index + match[0].length)
      const [label] = staticJavaStrings(labelExpression)
      if (!label) continue
      const suffixes = match[1] === 'require' ? [' are required'] : match[1] === 'normalizeMoney' ? [' must be between 0 and 1000000'] : match[1] === 'normalizeCode' ? [' is required', ' contains unsupported characters'] : [' is required']
      for (const suffix of suffixes) assert.notEqual(api.localizeBackendMessage(label + suffix), label + suffix, `${name}: missing field error ${label + suffix}`)
    }
  }
  for (const message of changedLabelMessages) assert.ok(covered.has(message), `scanner missed ${message}`)
})

test('system templates preserve tracking values, punctuation and literal placeholder-like input', () => {
  const cases = [
    ['Shipment #315 was recorded.', '已登记物流 #315。'],
    ['NXR received the inbound shipment from SF Express.', 'NXR 已签收 SF Express 的寄入包裹。'],
    ['顺丰 SF tracking: ZX-{count}-$&-001', '顺丰 SF 运单号：ZX-{count}-$&-001'],
    ['An order must contain between 1 and 500 cards', '每单须包含 1 至 500 张卡片'],
    ['Each intake may contain at most 12 cards under the current NXR policy', '按当前 NXR 规则，每份来件最多包含 12 张卡片'],
    ['Text exceeds 2000 characters', '内容超过 2000 个字符'],
    ['Packing check failed: 2 card(s) are missing the packing scan', '包装核对未通过：2 张卡片缺少装箱扫描'],
    ['Select between 1 and 200 unique intakes', '请选择 1 至 200 份不重复的来件'],
    ['Select between 1 and 500 unique cards', '请选择 1 至 500 张不重复的卡片'],
    ["Order belongs to merchant batch MB-42; use the batch's shared shipment workflow", '此订单属于代理批次 MB-42，请通过该批次的主包裹流程处理物流']
  ]
  for (const [english, chinese] of cases) {
    assert.equal(localizeKnownOrderText(english, 'zh-CN'), chinese)
    assert.equal(localizeKnownOrderText(chinese, 'en'), english)
  }
  for (const text of ['Shipment #ABC was recorded.', 'Text exceeds many characters', 'prefix Shipment #315 was recorded.', 'SF tracking: ABC\nCustomer note', 'Packing check failed: two card(s) are missing the packing scan', 'Custom field is required']) assert.equal(localizeKnownOrderText(text, 'zh-CN'), text)
})

test('only complete known default terms and turnaround text are translated', () => {
  const english = 'Please confirm the submitted item list, approved quote, and current NXR service terms before payment.'
  const chinese = '付款前，请确认提交的卡片清单、已批准报价及当前 NXR 服务条款。'
  assert.equal(localizeDefaultOrderText(english, 'zh-CN'), chinese)
  assert.equal(localizeDefaultOrderText(chinese, 'en'), english)
  assert.equal(localizeDefaultOrderText('以受理确认为准', 'en'), 'Subject to admission confirmation')
  assert.equal(localizeDefaultOrderText('Subject to admission confirmation', 'zh-CN'), '以受理确认为准')
  for (const value of [english + '\nCustom shipping terms.', chinese + ' 自定义条款', ' 以受理确认为准 ', 'Order approved', null]) {
    assert.equal(localizeDefaultOrderText(value, 'en'), value)
    assert.equal(localizeDefaultOrderText(value, 'zh-CN'), value)
  }
})

test('system timeline details localize while review, customer and operator remarks remain verbatim', () => {
  const systemEvent = Object.freeze({eventCode:'application_submitted',title:'Application submitted',detail:'NXR will review the item list and quoted amount before payment opens.'})
  assert.equal(localizeOrderEventDetail(systemEvent, 'zh-CN'), 'NXR 将先审核卡片清单和报价金额，再开放付款。')
  const shipment = {eventCode:'status_changed',title:'Cards shipped to NXR',detail:'SF tracking: 001-{count}-$&'}
  assert.equal(localizeOrderEventDetail(shipment, 'zh-CN'), 'SF 运单号：001-{count}-$&')
  for (const code of ['approved','rejected','information_requested','resubmitted','exception_resolved','quality_passed','quality_failed','work_task_completed','ticket_created','ticket_resolved','shipment_delivered','finance_review','shipping_change_approved']) {
    const detail = 'Warehouse intake is complete.'
    const event = Object.freeze({eventCode:code,title:'Custom operator title',detail})
    assert.equal(localizeOrderEventDetail(event, 'zh-CN'), detail)
  }
  assert.equal(localizeOrderEventDetail({title:'Additional information submitted',detail:'Warehouse intake is complete.'}, 'zh-CN'), 'Warehouse intake is complete.')
  assert.equal(localizeOrderEventDetail({eventCode:'new_custom_event',title:'Customer title',detail:'Warehouse intake is complete.'}, 'zh-CN'), 'Warehouse intake is complete.')
  assert.equal(systemEvent.detail, 'NXR will review the item list and quoted amount before payment opens.')
})

test('reactive locale updates existing titles, templates, errors and default terms without refetching', () => {
  const api = localeFixture()
  const source = reactive({title:'Application submitted',error:'An order must contain between 1 and 12 cards',terms:'以受理确认为准',detail:{eventCode:'shipment_created',title:'Shipment recorded',detail:'Shipment #38 was recorded.'}})
  const display = computed(() => [api.tx(source.title), api.localizeBackendMessage(source.error), api.localizeOrderTerms(source.terms), api.localizeOrderEventDetail(source.detail), api.tx('Uploading card {number}: {side}', {number:2,side:api.tx('Back')})])
  assert.deepEqual(display.value, ['Application submitted','An order must contain between 1 and 12 cards','Subject to admission confirmation','Shipment #38 was recorded.','Uploading card 2: Back'])
  api.i18n.global.locale.value = 'zh-CN'
  assert.deepEqual(display.value, ['申请已提交','每单须包含 1 至 12 张卡片','以受理确认为准','已登记物流 #38。','正在上传第 2 张卡片：背面'])
  api.i18n.global.locale.value = 'en'
  assert.equal(display.value[0], 'Application submitted')
  assert.equal(source.terms, '以受理确认为准')
  assert.equal(api.tx('卡片编码'), 'Card code')
  assert.equal(api.localizeBackendMessage('Custom inventory code note'), 'Custom inventory code note')
})

test('known prefixed return blockers and stored translated errors follow locale changes', () => {
  const api = localeFixture()
  api.i18n.global.locale.value = 'zh-CN'
  assert.equal(api.localizeBackendMessage('NXR-260915-001: Card needs its final grade'), 'NXR-260915-001: 请先完成最终评级')
  assert.equal(api.localizeBackendMessage('The physical certificate number does not match this card'), '实物证书号与该卡片不一致。')
  for (const english of changedLabelMessages) {
    const chinese = api.localizeBackendMessage(english)
    assert.notEqual(chinese, english)
    api.i18n.global.locale.value = 'en'
    assert.equal(api.localizeBackendMessage(chinese), english)
    api.i18n.global.locale.value = 'zh-CN'
  }
  const message = api.localizeBackendMessage('Return parcel created.')
  api.i18n.global.locale.value = 'en'
  assert.equal(api.localizeBackendMessage(message), 'Return parcel created.')
  assert.equal(api.localizeBackendMessage('NXR-260915-001: Operator custom reason'), 'NXR-260915-001: Operator custom reason')
})

test('hosted builds stay English and hide locale selection despite stale browser preferences', () => {
  for (const environment of ['java-remote','production','staging']) {
    const api = localeFixture(environment, 'zh-CN')
    assert.equal(api.allowLocaleSelection, false)
    assert.equal(api.i18n.global.locale.value, 'en')
    assert.equal(api.localizeOrderTerms('以受理确认为准'), 'Subject to admission confirmation')
  }
  const local = localeFixture('java-stage','zh-CN')
  assert.equal(local.allowLocaleSelection, true)
  assert.equal(local.i18n.global.locale.value, 'zh-CN')
})

test('review decisions and configuration saves retain custom note and frozen text payloads', async () => {
  const source = readFileSync(new URL('../src/views/nxr/orders/components/OrderAdmissionPanel.vue', import.meta.url), 'utf8')
  const script = parse(source).descriptor.scriptSetup.content.replace(/^import .*\n/gm, '')
  const api = localeFixture('java-stage','zh-CN'), scope = effectScope(), posts = [], decisions = []
  const note = 'Warehouse intake is complete.\n操作员原文'
  const terms = 'Please confirm custom terms.\n原始冻结条款 {count}'
  const context = {computed,reactive,ref,watch,defineProps:() => ({orderId:42,orderAmount:23,currencyCode:'USD'}),defineEmits:() => () => {},localizeOrderTerms:api.localizeOrderTerms,localizeOrderEventDetail:api.localizeOrderEventDetail,
    getCurrentInstance:() => ({proxy:{$tx:api.tx,parseTime:v => v,$modal:{msgWarning:() => {},msgSuccess:() => {},confirm:async () => {}}}}),
    getOrderAdmission:async () => ({data:{admissionStatus:'pending_review',admissionRevision:3,termsText:terms,turnaroundText:'Custom turnaround'}}),
    getOrderAdmissionConfig:async () => ({data:{paymentDeadlineHours:48,maxCardsPerOrder:500,termsVersion:'custom-1',termsText:terms,turnaroundText:'Custom turnaround'}}),
    updateOrderAdmissionConfig:async value => {posts.push(JSON.parse(JSON.stringify(value)));return {data:value}},
    decideOrderAdmission:async (id,value) => {decisions.push([id,JSON.parse(JSON.stringify(value))]);return {data:{admissionStatus:'approved',admissionRevision:4,termsText:terms}}}}
  scope.run(() => vm.runInNewContext(script + '\nglobalThis.state = {admission,decisionNote,configForm,loadConfig,saveConfig,submitDecision};', context))
  try {
    await nextTick(); await Promise.resolve()
    await context.state.loadConfig()
    api.i18n.global.locale.value = 'en'
    await context.state.saveConfig()
    assert.equal(posts[0].termsText, terms)
    assert.equal(posts[0].turnaroundText, 'Custom turnaround')
    context.state.decisionNote.value = note
    await context.state.submitDecision('approve')
    assert.equal(decisions[0][1].note, note)
    assert.equal(decisions[0][1].expectedRevision, 3)
    assert.equal(context.state.admission.value.termsText, terms)
  } finally {scope.stop()}
})
