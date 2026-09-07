import assert from 'node:assert/strict'
import test, { type TestContext } from 'node:test'

import {
  convertToLosslessWebp,
  getWebpConcurrency,
} from '../src/lib/webp-converter.ts'

interface PostedConversion {
  id: number
  file: File
}

class FakeWorker {
  static instances: FakeWorker[] = []
  static active = 0
  static maxActive = 0
  static throwOnPost = new Set<string>()

  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: ((event: ErrorEvent) => void) | null = null
  posted?: PostedConversion
  terminated = false

  constructor(_url: URL, _options: WorkerOptions) {
    FakeWorker.instances.push(this)
  }

  postMessage(message: PostedConversion): void {
    if (FakeWorker.throwOnPost.has(message.file.name)) {
      throw new Error('injected postMessage failure')
    }
    this.posted = message
    FakeWorker.active += 1
    FakeWorker.maxActive = Math.max(FakeWorker.maxActive, FakeWorker.active)
  }

  terminate(): void {
    if (this.terminated) return
    this.terminated = true
    if (this.posted) FakeWorker.active -= 1
  }

  succeed(bytes = [1, 2, 3]): void {
    assert(this.posted)
    const buffer = new Uint8Array(bytes).buffer
    this.onmessage?.({
      data: { id: this.posted.id, ok: true, buffer },
    } as MessageEvent)
  }

  fail(message = 'injected worker failure'): void {
    assert(this.posted)
    this.onmessage?.({
      data: { id: this.posted.id, ok: false, error: message },
    } as MessageEvent)
  }

  crash(): void {
    assert(this.posted)
    this.onerror?.({} as ErrorEvent)
  }

  static reset(): void {
    FakeWorker.instances = []
    FakeWorker.active = 0
    FakeWorker.maxActive = 0
    FakeWorker.throwOnPost.clear()
  }

  static live(): FakeWorker[] {
    return FakeWorker.instances.filter((worker) => worker.posted && !worker.terminated)
  }
}

function installFakeRuntime(
  context: TestContext,
  capabilities = { hardwareConcurrency: 8, deviceMemory: 8 },
): void {
  const workerDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'Worker')
  const navigatorDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'navigator')
  FakeWorker.reset()

  Object.defineProperty(globalThis, 'Worker', {
    configurable: true,
    writable: true,
    value: FakeWorker,
  })
  Object.defineProperty(globalThis, 'navigator', {
    configurable: true,
    value: capabilities,
  })

  context.after(() => {
    restoreGlobal('Worker', workerDescriptor)
    restoreGlobal('navigator', navigatorDescriptor)
  })
}

function restoreGlobal(
  name: PropertyKey,
  descriptor: PropertyDescriptor | undefined,
): void {
  if (descriptor) Object.defineProperty(globalThis, name, descriptor)
  else Reflect.deleteProperty(globalThis, name)
}

function fakeFile(name: string): File {
  return new File([new Uint8Array([7, 8, 9])], name, {
    type: 'application/octet-stream',
  })
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

test('getWebpConcurrency selects one worker only for low-resource devices', () => {
  assert.equal(getWebpConcurrency({}), 2)
  assert.equal(getWebpConcurrency({ hardwareConcurrency: 8 }), 2)
  assert.equal(getWebpConcurrency({ hardwareConcurrency: 4, deviceMemory: 4 }), 2)
  assert.equal(getWebpConcurrency({ hardwareConcurrency: 2, deviceMemory: 8 }), 1)
  assert.equal(getWebpConcurrency({ hardwareConcurrency: 1 }), 1)
  assert.equal(getWebpConcurrency({ hardwareConcurrency: 8, deviceMemory: 2 }), 1)
  assert.equal(getWebpConcurrency({ hardwareConcurrency: 3, deviceMemory: 8 }), 1)
})

test('the pool runs at most two WebP workers and drains queued conversions', async (context) => {
  installFakeRuntime(context)

  const conversions = Array.from({ length: 5 }, (_, index) =>
    convertToLosslessWebp(fakeFile(`input-${index}.bin`)),
  )

  assert.equal(FakeWorker.instances.length, 2)
  assert.equal(FakeWorker.active, 2)

  while (FakeWorker.live().length > 0) {
    FakeWorker.live()[0]?.succeed()
    assert.ok(FakeWorker.active <= 2)
  }

  const outputs = await Promise.all(conversions)
  assert.equal(FakeWorker.instances.length, 5)
  assert.equal(FakeWorker.maxActive, 2)
  assert.equal(FakeWorker.active, 0)
  assert.ok(FakeWorker.instances.every((worker) => worker.terminated))
  assert.ok(outputs.every((output) => output.type === 'image/webp' && output.size === 3))
})

test('queued and running aborts reject immediately without blocking the pool', async (context) => {
  installFakeRuntime(context)
  const runningController = new AbortController()
  const queuedController = new AbortController()

  const running = convertToLosslessWebp(fakeFile('running.bin'), {
    signal: runningController.signal,
  })
  const steady = convertToLosslessWebp(fakeFile('steady.bin'))
  const queued = convertToLosslessWebp(fakeFile('queued.bin'), {
    signal: queuedController.signal,
  })
  const next = convertToLosslessWebp(fakeFile('next.bin'))
  const runningRejected = assert.rejects(running, isAbortError)
  const queuedRejected = assert.rejects(queued, isAbortError)

  assert.equal(FakeWorker.instances.length, 2)
  queuedController.abort()
  assert.equal(FakeWorker.instances.length, 2)

  const firstWorker = FakeWorker.instances[0]
  assert(firstWorker)
  runningController.abort()
  assert.equal(firstWorker.terminated, true)
  assert.equal(FakeWorker.instances.length, 3)
  assert.equal(FakeWorker.active, 2)

  for (const worker of FakeWorker.live()) worker.succeed()
  await Promise.all([runningRejected, queuedRejected, steady, next])
  assert.equal(FakeWorker.active, 0)
})

test('worker responses, runtime errors and synchronous postMessage errors release their slots', async (context) => {
  installFakeRuntime(context)
  FakeWorker.throwOnPost.add('post-error.bin')

  const first = convertToLosslessWebp(fakeFile('first.bin'))
  const second = convertToLosslessWebp(fakeFile('second.bin'))
  const responseError = convertToLosslessWebp(fakeFile('response-error.bin'))
  const workerError = convertToLosslessWebp(fakeFile('worker-error.bin'))
  const postError = convertToLosslessWebp(fakeFile('post-error.bin'))
  const recovered = convertToLosslessWebp(fakeFile('recovered.bin'))
  const responseErrorRejected = assert.rejects(responseError, /injected response failure/)
  const workerErrorRejected = assert.rejects(workerError, /意外停止/)
  const postErrorRejected = assert.rejects(postError, /无法启动/)

  const firstWorker = FakeWorker.instances[0]
  assert(firstWorker)
  firstWorker.succeed()
  const responseFailingWorker = FakeWorker.instances[2]
  assert(responseFailingWorker)
  responseFailingWorker.fail('injected response failure')
  const crashingWorker = FakeWorker.instances[3]
  assert(crashingWorker)
  crashingWorker.crash()

  assert.equal(FakeWorker.instances[4]?.posted, undefined)
  assert.equal(FakeWorker.instances[4]?.terminated, true)
  assert.equal(FakeWorker.instances[5]?.posted?.file.name, 'recovered.bin')
  assert.equal(FakeWorker.active, 2)

  for (const worker of FakeWorker.live()) worker.succeed()
  await Promise.all([
    first,
    second,
    responseErrorRejected,
    workerErrorRejected,
    postErrorRejected,
    recovered,
  ])
  assert.equal(FakeWorker.maxActive, 2)
  assert.equal(FakeWorker.active, 0)
})

test('conversion uses no fixed-duration timer', async (context) => {
  installFakeRuntime(context)
  const timerDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'setTimeout')
  Object.defineProperty(globalThis, 'setTimeout', {
    configurable: true,
    writable: true,
    value: () => {
      throw new Error('conversion attempted to schedule a timeout')
    },
  })
  context.after(() => restoreGlobal('setTimeout', timerDescriptor))

  const conversion = convertToLosslessWebp(fakeFile('untimed.bin'))
  FakeWorker.live()[0]?.succeed()
  const output = await conversion
  assert.equal(output.type, 'image/webp')
})
