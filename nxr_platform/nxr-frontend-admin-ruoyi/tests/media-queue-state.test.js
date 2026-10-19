import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import vm from 'node:vm'
import { parse, compileScript, compileTemplate, compileStyle } from '@vue/compiler-sfc'
import {
  canPublishMediaRow, canMarkMediaClientPushed, mediaUploadPresentation, createMediaClientPushAction
} from '../src/api/nxr/mediaQueueState.js'
import { publicationSelection } from '../src/api/nxr/mediaPublication.js'
import chinese from '../src/i18n/literal-zh-CN.js'

const version = id => id.toString(16).padStart(64, '0')
const row = (changes = {}) => ({ submissionId: 1, uploadStatus: 'not_started', uploadInterrupted: false,
  readyToPublish: true, hasStagedFront: true, hasStagedBack: true, stagedVersion: version(1), ...changes })
const deferred = () => {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}

test('expired server projection is retryable, while fresh or unprojected uploading stays locked', () => {
  const interrupted = row({ uploadStatus: 'failed', uploadInterrupted: true, uploadStartedAt: '2000-01-01T00:00:00' })
  assert.equal(canPublishMediaRow(interrupted), true)
  assert.equal(canMarkMediaClientPushed(interrupted), false)
  assert.equal(mediaUploadPresentation(interrupted).label, 'Interrupted, retry available')
  for (const startedAt of ['2000-01-01T00:00:00', '2099-01-01T00:00:00', null]) {
    const uploading = row({ uploadStatus: 'uploading', uploadStartedAt: startedAt })
    assert.equal(canPublishMediaRow(uploading), false)
    assert.equal(canMarkMediaClientPushed(uploading), false)
    assert.equal(mediaUploadPresentation(uploading).label, 'Uploading')
  }
  assert.equal(canPublishMediaRow(row({ uploadStatus: 'uploading', uploadInterrupted: true })), false)
})

test('ready new, failed and replacement pairs can publish; only uploaded rows can be marked client pushed', () => {
  for (const status of ['not_started', 'failed', 'uploaded', 'uploading', 'client_pushed']) {
    const item = row({ uploadStatus: status })
    assert.equal(canPublishMediaRow(item), ['not_started', 'failed', 'uploaded'].includes(status))
    assert.equal(canMarkMediaClientPushed(item), status === 'uploaded')
    assert.equal(canPublishMediaRow(item, true), false)
    assert.equal(canMarkMediaClientPushed(item, true), false)
  }
  assert.deepEqual(mediaUploadPresentation(row({ uploadStatus: 'failed' })), { label: 'Failed', type: 'danger' })
})

test('an interrupted one-sided or incomplete version cannot be selected or promise an available retry', () => {
  for (const change of [{ hasStagedBack: false }, { hasStagedFront: false }, { stagedBackMissing: true },
    { stagedFrontMissing: true }, { stagedVersion: null }, { readyToPublish: false }]) {
    const item = row({ uploadStatus: 'failed', uploadInterrupted: true, ...change })
    assert.equal(canPublishMediaRow(item), false)
    assert.equal(mediaUploadPresentation(item).label, 'Upload interrupted')
  }
})

test('batch selection includes the interrupted pair and freezes its version before later queue updates', () => {
  const items = [row({ uploadStatus: 'failed', uploadInterrupted: true }),
    row({ submissionId: 2, uploadStatus: 'uploading', stagedVersion: version(2) }),
    row({ submissionId: 3, uploadStatus: 'client_pushed', stagedVersion: version(3) }),
    row({ submissionId: 4, hasStagedBack: false, stagedVersion: version(4) }),
    row({ submissionId: 5, uploadStatus: 'failed', stagedVersion: version(5) })]
  const selected = items.filter(item => canPublishMediaRow(item))
  const frozen = publicationSelection(selected.map(item => item.submissionId),
    Object.fromEntries(selected.map(item => [item.submissionId, item.stagedVersion])))
  items[0].stagedVersion = version(99)
  assert.deepEqual(frozen, { ids: [1, 5], versions: { 1: version(1), 5: version(5) } })
})

function actionHarness(options = {}) {
  const state = { current: row({ uploadStatus: 'uploaded' }), busy: null, confirmations: 0, requests: 0, refreshes: 0, successes: 0 }
  const action = createMediaClientPushAction({
    rowForId: id => state.current?.submissionId === id ? state.current : undefined,
    isBusy: () => state.busy !== null,
    setBusy: id => { state.busy = id },
    confirm: async () => { state.confirmations++; await options.confirm?.(state) },
    submit: async id => { state.requests++; await options.submit?.(state, id) },
    refresh: async () => { state.refreshes++; await options.refresh?.(state) },
    onSuccess: () => { state.successes++ }
  })
  return { state, action }
}

test('client-pushed guard rejects every non-uploaded state before confirmation or a request', async () => {
  for (const status of ['not_started', 'failed', 'uploading', 'client_pushed']) {
    const h = actionHarness()
    h.state.current.uploadStatus = status
    assert.equal(await h.action(1), false)
    assert.equal(h.state.confirmations, 0)
    assert.equal(h.state.requests, 0)
  }
})

test('repeated clicks share one confirmation and one request, including while the request is pending', async () => {
  const confirm = deferred(), submit = deferred()
  const h = actionHarness({ confirm: () => confirm.promise, submit: () => submit.promise })
  const first = h.action(1)
  assert.equal(h.state.busy, 1)
  assert.equal(await h.action(1), false)
  assert.equal(h.state.confirmations, 1)
  confirm.resolve()
  await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
  assert.equal(h.state.requests, 1)
  assert.equal(await h.action(1), false)
  submit.resolve()
  assert.equal(await first, true)
  assert.equal(h.state.requests, 1)
  assert.equal(h.state.successes, 1)
  assert.equal(h.state.refreshes, 1)
  assert.equal(h.state.busy, null)
})

test('a row changing or disappearing during confirmation is rechecked without a POST', async () => {
  for (const current of [row({ uploadStatus: 'not_started' }), undefined]) {
    const h = actionHarness({ confirm: state => { state.current = current } })
    assert.equal(await h.action(1), false)
    assert.equal(h.state.requests, 0)
    assert.equal(h.state.successes, 0)
    assert.equal(h.state.busy, null)
  }
})

test('confirmation cancellation releases the guard without submitting or refreshing', async () => {
  const h = actionHarness({ confirm: () => { throw new Error('cancel') } })
  assert.equal(await h.action(1), false)
  assert.equal(h.state.requests, 0)
  assert.equal(h.state.refreshes, 0)
  assert.equal(h.state.busy, null)
})

test('a backend conflict refreshes the changed row, reports no success and prevents another invalid POST', async () => {
  const conflict = Object.assign(new Error('state changed'), { status: 409 })
  const h = actionHarness({ submit: () => { throw conflict }, refresh: state => { state.current.uploadStatus = 'not_started' } })
  await assert.rejects(h.action(1), error => error === conflict)
  assert.equal(h.state.successes, 0)
  assert.equal(h.state.refreshes, 1)
  assert.equal(h.state.busy, null)
  assert.equal(await h.action(1), false)
  assert.equal(h.state.requests, 1)
})

test('a lost response is not retried automatically and a committed client-pushed row is disabled after readback', async () => {
  const lost = Object.assign(new Error('response lost'), { code: 'ERR_NETWORK' })
  const h = actionHarness({ submit: state => { state.serverStatus = 'client_pushed'; throw lost },
    refresh: state => { state.current.uploadStatus = state.serverStatus } })
  await assert.rejects(h.action(1), error => error === lost)
  assert.equal(h.state.requests, 1)
  assert.equal(h.state.successes, 0)
  assert.equal(h.state.refreshes, 1)
  assert.equal(canMarkMediaClientPushed(h.state.current), false)
  assert.equal(await h.action(1), false)
  assert.equal(h.state.requests, 1)
})

test('interruption presentation and the exact backend hint have Chinese translations', () => {
  const labels = [mediaUploadPresentation(row({ uploadStatus: 'failed', uploadInterrupted: true })).label,
    mediaUploadPresentation(row({ uploadStatus: 'failed', uploadInterrupted: true, hasStagedBack: false })).label,
    'Upload interrupted. Retry publishing when both images are ready.']
  for (const label of labels) {
    assert.equal(typeof chinese[label], 'string')
    assert.match(chinese[label], /[\u4e00-\u9fff]/u)
  }
})

function compileUploadComponent() {
  const filename = new URL('../src/views/nxr/upload/index.vue', import.meta.url).pathname
  const { descriptor, errors } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  assert.deepEqual(errors, [])
  const script = compileScript(descriptor, { id: 'media-queue-state-component' })
  const template = compileTemplate({ source: descriptor.template.content, filename, id: 'media-queue-state-component',
    compilerOptions: { bindingMetadata: script.bindings } })
  assert.deepEqual(template.errors, [])
  return { filename, descriptor, script, template }
}

test('the real upload component has no undefined template methods, including summary status formatting', () => {
  const { filename, descriptor, script, template } = compileUploadComponent()
  const unboundCalls = [...new Set([...template.code.matchAll(/_ctx\.([\w$]+)\s*\(/g)].map(match => match[1]))]
  // $tx is the application's injected global. Every other method must come from setup.
  assert.deepEqual(unboundCalls, ['$tx'])
  for (const name of ['mediaUploadPresentation', 'canPublishMediaRow', 'canMarkMediaClientPushed']) {
    assert.equal(typeof script.bindings[name], 'string')
  }
  for (const style of descriptor.styles) {
    assert.deepEqual(compileStyle({ source: style.content, filename, id: 'media-queue-state-component', scoped: style.scoped }).errors, [])
  }
})

test('the actual component busy callback resumes publication polling only after the action guard is released', () => {
  const { descriptor, script } = compileUploadComponent()
  const declaration = script.scriptSetupAst.flatMap(node => node.type === 'VariableDeclaration' ? node.declarations : [])
    .find(node => node.id.name === 'clientPushAction')
  const property = declaration.init.arguments[0].properties.find(node => node.key.name === 'setBusy')
  const source = descriptor.scriptSetup.content.slice(property.value.start, property.value.end)
  let polls = 0
  const busy = { value: null }
  const updateBusy = vm.runInNewContext(`(${source})`, { clientPushLoadingId: busy, armPublicationRefresh: () => { polls++ } })
  updateBusy(1)
  assert.equal(busy.value, 1)
  assert.equal(polls, 0)
  updateBusy(null)
  assert.equal(busy.value, null)
  assert.equal(polls, 1)
})
