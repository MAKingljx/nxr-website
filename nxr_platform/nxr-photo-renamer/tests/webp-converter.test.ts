import assert from 'node:assert/strict'
import test, { type TestContext } from 'node:test'

import { getProcessingConcurrency } from '../src/lib/performance-policy.ts'
import {
  convertToLosslessWebp,
  convertToWebp,
  disposeWebpWorkers,
  getActiveWebpCount,
  getActualWebpConcurrency,
  getWebpConcurrency,
} from '../src/lib/webp-converter.ts'

interface PostedConversion {
  id: number
  file: File
  mode: 'quality' | 'lossless'
  quality?: number
}

class FakeWorker {
  static instances: FakeWorker[] = []
  static active = 0
  static maxActive = 0
  static throwOnPost = new Set<string>()

  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: ((event: ErrorEvent) => void) | null = null
  onmessageerror: ((event: MessageEvent) => void) | null = null
  posted?: PostedConversion
  posts: PostedConversion[] = []
  terminated = false

  constructor(_url: URL, _options: WorkerOptions) {
    FakeWorker.instances.push(this)
  }

  postMessage(message: PostedConversion): void {
    if (FakeWorker.throwOnPost.has(message.file.name)) {
      throw new Error('injected postMessage failure')
    }
    assert.equal(this.posted, undefined, 'worker slot must process one task at a time')
    this.posted = message
    this.posts.push(message)
    FakeWorker.active += 1
    FakeWorker.maxActive = Math.max(FakeWorker.maxActive, FakeWorker.active)
  }

  terminate(): void {
    if (this.terminated) return
    this.terminated = true
    if (this.posted) {
      this.posted = undefined
      FakeWorker.active -= 1
    }
  }

  respond(data: object): void {
    assert(this.posted)
    this.posted = undefined
    FakeWorker.active -= 1
    this.onmessage?.({ data } as MessageEvent)
  }

  succeed(bytes = [1, 2, 3]): void {
    const posted = this.posted
    assert(posted)
    this.respond({ id: posted.id, ok: true, buffer: new Uint8Array(bytes).buffer })
  }

  fail(message = 'injected worker failure'): void {
    const posted = this.posted
    assert(posted)
    this.respond({ id: posted.id, ok: false, error: message })
  }

  wrongId(): void {
    assert(this.posted)
    this.onmessage?.({ data: { id: this.posted.id + 1000, ok: true, buffer: new ArrayBuffer(1) } } as MessageEvent)
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
    return FakeWorker.instances.filter(worker => worker.posted && !worker.terminated)
  }

  static running(name: string): FakeWorker {
    const worker = FakeWorker.live().find(candidate => candidate.posted?.file.name === name)
    assert(worker, `missing running worker for ${name}`)
    return worker
  }
}

function installFakeRuntime(
  context: TestContext,
  capabilities = { hardwareConcurrency: 4, deviceMemory: 4 },
): void {
  disposeWebpWorkers()
  const workerDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'Worker')
  const navigatorDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'navigator')
  const desktopDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'nxrDesktop')
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
  Reflect.deleteProperty(globalThis, 'nxrDesktop')

  context.after(() => {
    disposeWebpWorkers()
    restoreGlobal('Worker', workerDescriptor)
    restoreGlobal('navigator', navigatorDescriptor)
    restoreGlobal('nxrDesktop', desktopDescriptor)
  })
}

function restoreGlobal(name: PropertyKey, descriptor: PropertyDescriptor | undefined): void {
  if (descriptor) Object.defineProperty(globalThis, name, descriptor)
  else Reflect.deleteProperty(globalThis, name)
}

function fakeFile(name: string): File {
  return new File([new Uint8Array([7, 8, 9])], name, { type: 'application/octet-stream' })
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

test('shared static policy uses all cores that fit the per-lane memory budget', () => {
  assert.equal(getProcessingConcurrency('webp', {}), 2)
  assert.equal(getProcessingConcurrency('webp', { hardwareConcurrency: 32 }), 2)
  assert.equal(getProcessingConcurrency('webp', { deviceMemory: 64 }), 2)
  assert.equal(getProcessingConcurrency('webp', { hardwareConcurrency: 1 }), 1)
  assert.equal(getProcessingConcurrency('webp', { deviceMemory: 1 }), 1)
  assert.equal(getProcessingConcurrency('scan', { deviceMemory: 3 }), 2)
  assert.equal(getProcessingConcurrency('webp', { hardwareConcurrency: 2, deviceMemory: 32 }), 2)
  assert.equal(getProcessingConcurrency('webp', { hardwareConcurrency: 32, deviceMemory: 8 }), 4)
  assert.equal(getProcessingConcurrency('scan', { hardwareConcurrency: 32, deviceMemory: 8 }), 5)
  assert.equal(getProcessingConcurrency('thumbnail', { hardwareConcurrency: 32, deviceMemory: 8 }), 8)
  assert.equal(getProcessingConcurrency('webp', { hardwareConcurrency: 8, deviceMemory: 8 }), 4)
  assert.equal(getProcessingConcurrency('webp', { hardwareConcurrency: 14, deviceMemory: 36 }), 14)
  assert.equal(getProcessingConcurrency('scan', { hardwareConcurrency: 24, deviceMemory: 36 }), 24)
  assert.equal(getProcessingConcurrency('webp', { hardwareConcurrency: 32, deviceMemory: 64 }), 32)
  assert.equal(getProcessingConcurrency('thumbnail', { hardwareConcurrency: 64, deviceMemory: 64 }), 64)
})

test('a live shrink drains in-flight work and blocks idle slots from bypassing the active limit', async (context) => {
  installFakeRuntime(context, { hardwareConcurrency: 4, deviceMemory: 8 })
  Object.defineProperty(globalThis, 'nxrDesktop', {
    configurable: true,
    value: {
      capabilities: { hardwareConcurrency: 4, deviceMemory: 8 },
      getPerformanceMetrics: async () => ({
        workingSetBytes: 6.2 * 1024 ** 3,
        privateBytes: 6.2 * 1024 ** 3,
        measuredAtMs: Date.now(),
      }),
    },
  })
  const conversions = Array.from({ length: 8 }, (_, index) =>
    convertToWebp(fakeFile(`adaptive-${index}.bin`)),
  )
  assert.equal(FakeWorker.active, 4)
  assert.equal(getActiveWebpCount(), 4)
  await new Promise<void>(resolve => setImmediate(resolve))
  assert.ok(getActualWebpConcurrency() < 4)

  const running = FakeWorker.live()[0]!
  running.succeed()
  assert.equal(running.terminated, true, 'a completed slot is released after the live shrink')
  assert.equal(FakeWorker.active, 3, 'queued work must wait while active work exceeds the new limit')
  assert.equal(getActiveWebpCount(), 3)

  while (FakeWorker.live().length > 0) FakeWorker.live()[0]!.succeed()
  await Promise.all(conversions)
  assert.equal(getActiveWebpCount(), 0)
  assert.ok(FakeWorker.maxActive <= 4)
})

test('desktop capabilities take priority over browser-clamped values', (context) => {
  installFakeRuntime(context, { hardwareConcurrency: 8, deviceMemory: 8 })
  Object.defineProperty(globalThis, 'nxrDesktop', {
    configurable: true,
    value: { capabilities: { hardwareConcurrency: 16, deviceMemory: 32 } },
  })
  assert.equal(getWebpConcurrency(), 16)
})

test('quality and lossless APIs send explicit modes and quality defaults', async (context) => {
  installFakeRuntime(context)
  const quality = convertToWebp(fakeFile('quality.bin'))
  const lossless = convertToLosslessWebp(fakeFile('lossless.bin'))
  assert.deepEqual(FakeWorker.instances.map(worker => worker.posted?.mode), ['quality', 'lossless'])
  assert.equal(FakeWorker.instances[0]?.posted?.quality, 0.92)
  assert.equal(FakeWorker.instances[1]?.posted?.quality, undefined)
  FakeWorker.live().forEach(worker => worker.succeed())
  await Promise.all([quality, lossless])
  await assert.rejects(convertToWebp(fakeFile('invalid.bin'), { quality: 1.1 }), /质量/)
})

test('bounded workers are reused while queued conversions drain', async (context) => {
  installFakeRuntime(context)
  const conversions = Array.from({ length: 5 }, (_, index) =>
    convertToWebp(fakeFile(`input-${index}.bin`)),
  )

  assert.equal(FakeWorker.instances.length, 2)
  while (FakeWorker.live().length > 0) {
    FakeWorker.live()[0]?.succeed()
    assert.ok(FakeWorker.active <= 2)
  }

  const outputs = await Promise.all(conversions)
  assert.equal(FakeWorker.instances.length, 2)
  assert.equal(FakeWorker.instances.reduce((sum, worker) => sum + worker.posts.length, 0), 5)
  assert.equal(FakeWorker.maxActive, 2)
  assert.equal(FakeWorker.active, 0)
  assert.ok(FakeWorker.instances.every(worker => !worker.terminated))
  assert.ok(outputs.every(output => output.type === 'image/webp' && output.size === 3))
})

test('queued and running aborts reject immediately and replace only the cancelled slot', async (context) => {
  installFakeRuntime(context)
  const runningController = new AbortController()
  const queuedController = new AbortController()
  const running = convertToWebp(fakeFile('running.bin'), { signal: runningController.signal })
  const steady = convertToWebp(fakeFile('steady.bin'))
  const queued = convertToWebp(fakeFile('queued.bin'), { signal: queuedController.signal })
  const next = convertToWebp(fakeFile('next.bin'))
  const runningRejected = assert.rejects(running, isAbortError)
  const queuedRejected = assert.rejects(queued, isAbortError)

  queuedController.abort()
  assert.equal(FakeWorker.instances.length, 2)
  const cancelledWorker = FakeWorker.running('running.bin')
  runningController.abort()
  assert.equal(cancelledWorker.terminated, true)
  assert.equal(FakeWorker.instances.length, 3)
  assert.equal(FakeWorker.active, 2)

  FakeWorker.live().forEach(worker => worker.succeed())
  await Promise.all([runningRejected, queuedRejected, steady, next])
  assert.equal(FakeWorker.active, 0)
})

test('wrong ids are ignored and native memory errors fail one task without replacing its healthy worker', async (context) => {
  installFakeRuntime(context, { hardwareConcurrency: 1, deviceMemory: 8 })
  const first = convertToWebp(fakeFile('first.bin'))
  const second = convertToWebp(fakeFile('second.bin'))
  const rejected = assert.rejects(first, /原生编码内存不足/)
  const worker = FakeWorker.running('first.bin')
  worker.wrongId()
  assert.equal(worker.posted?.file.name, 'first.bin')
  worker.fail('原生编码内存不足')
  assert.equal(FakeWorker.instances.length, 1)
  assert.equal(worker.posted?.file.name, 'second.bin')
  worker.succeed()
  await Promise.all([rejected, second])
})

test('worker crashes and synchronous post errors recycle one slot and continue the queue', async (context) => {
  installFakeRuntime(context)
  FakeWorker.throwOnPost.add('post-error.bin')
  const crashing = convertToWebp(fakeFile('crashing.bin'))
  const steady = convertToWebp(fakeFile('steady.bin'))
  const postError = convertToWebp(fakeFile('post-error.bin'))
  const recovered = convertToWebp(fakeFile('recovered.bin'))
  const crashRejected = assert.rejects(crashing, /意外停止/)
  const postRejected = assert.rejects(postError, /无法启动/)

  const crashedWorker = FakeWorker.running('crashing.bin')
  crashedWorker.crash()
  assert.equal(crashedWorker.terminated, true)
  assert.equal(FakeWorker.running('recovered.bin').terminated, false)
  assert.equal(FakeWorker.active, 2)
  FakeWorker.live().forEach(worker => worker.succeed())

  await Promise.all([crashRejected, steady, postRejected, recovered])
  assert.equal(FakeWorker.maxActive, 2)
  assert.equal(FakeWorker.active, 0)
})

test('conversion schedules no fixed-duration timeout', async (context) => {
  installFakeRuntime(context)
  const timerDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'setTimeout')
  Object.defineProperty(globalThis, 'setTimeout', {
    configurable: true,
    writable: true,
    value: () => { throw new Error('conversion attempted to schedule a timeout') },
  })
  context.after(() => restoreGlobal('setTimeout', timerDescriptor))

  const conversion = convertToWebp(fakeFile('untimed.bin'))
  FakeWorker.live()[0]?.succeed()
  const output = await conversion
  assert.equal(output.type, 'image/webp')
})
