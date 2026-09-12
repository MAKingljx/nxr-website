import assert from 'node:assert/strict'
import test, { type TestContext } from 'node:test'

import {
  cancelScan,
  getActiveScanCount,
  getScanConcurrency,
  scanPhoto,
} from '../src/lib/scanner.ts'

interface PostedScan {
  id: number
  file: File
  mode: 'standard' | 'deep'
}

class FakeWorker {
  static instances: FakeWorker[] = []
  static active = 0
  static maxActive = 0
  static throwOnPost = new Set<string>()

  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: ((event: ErrorEvent) => void) | null = null
  current?: PostedScan
  terminated = false

  constructor(_url: URL, _options: WorkerOptions) {
    FakeWorker.instances.push(this)
  }

  postMessage(message: PostedScan): void {
    if (FakeWorker.throwOnPost.has(message.file.name)) throw new Error('injected post failure')
    assert.equal(this.current, undefined)
    this.current = message
    FakeWorker.active += 1
    FakeWorker.maxActive = Math.max(FakeWorker.maxActive, FakeWorker.active)
  }

  terminate(): void {
    if (this.terminated) return
    this.terminated = true
    this.releaseCurrent()
  }

  wrongId(): void {
    assert(this.current)
    this.onmessage?.({ data: { id: this.current.id + 10_000, certIds: ['wrong'], qrTexts: [] } } as MessageEvent)
  }

  succeed(certId = '7123456789'): void {
    const posted = this.releaseCurrent()
    assert(posted)
    this.onmessage?.({
      data: { id: posted.id, certIds: [certId], qrTexts: [`nxrgrading.com/card/${certId}`] },
    } as MessageEvent)
  }

  crash(): void {
    assert(this.releaseCurrent())
    this.onerror?.({} as ErrorEvent)
  }

  private releaseCurrent(): PostedScan | undefined {
    const posted = this.current
    if (posted) {
      this.current = undefined
      FakeWorker.active -= 1
    }
    return posted
  }

  static reset(): void {
    FakeWorker.instances = []
    FakeWorker.active = 0
    FakeWorker.maxActive = 0
    FakeWorker.throwOnPost.clear()
  }

  static running(): FakeWorker[] {
    return FakeWorker.instances.filter(worker => worker.current && !worker.terminated)
  }
}

class FakeClock {
  timers: Array<{ callback: () => void; active: boolean }> = []

  setTimeout = (callback: () => void): ReturnType<typeof setTimeout> => {
    const timer = { callback, active: true }
    this.timers.push(timer)
    return timer as unknown as ReturnType<typeof setTimeout>
  }

  clearTimeout = (timer: ReturnType<typeof setTimeout>): void => {
    const item = timer as unknown as { active: boolean }
    item.active = false
  }

  fire(index: number): void {
    const timer = this.timers[index]
    assert(timer?.active)
    timer.active = false
    timer.callback()
  }
}

function installFakeRuntime(
  context: TestContext,
  capabilities = { hardwareConcurrency: 4, deviceMemory: 4 },
): FakeClock {
  cancelScan()
  const workerDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'Worker')
  const navigatorDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'navigator')
  const setTimeoutDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'setTimeout')
  const clearTimeoutDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'clearTimeout')
  const clock = new FakeClock()
  FakeWorker.reset()

  Object.defineProperty(globalThis, 'Worker', { configurable: true, writable: true, value: FakeWorker })
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: capabilities })
  Object.defineProperty(globalThis, 'setTimeout', { configurable: true, writable: true, value: clock.setTimeout })
  Object.defineProperty(globalThis, 'clearTimeout', { configurable: true, writable: true, value: clock.clearTimeout })

  context.after(() => {
    cancelScan()
    restoreGlobal('Worker', workerDescriptor)
    restoreGlobal('navigator', navigatorDescriptor)
    restoreGlobal('setTimeout', setTimeoutDescriptor)
    restoreGlobal('clearTimeout', clearTimeoutDescriptor)
  })
  return clock
}

function restoreGlobal(name: PropertyKey, descriptor: PropertyDescriptor | undefined): void {
  if (descriptor) Object.defineProperty(globalThis, name, descriptor)
  else Reflect.deleteProperty(globalThis, name)
}

function fakeFile(name: string): File {
  return new File(['fixture'], name, { type: 'image/jpeg' })
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

test('getScanConcurrency exposes a high-memory search ceiling without expanding low-memory devices', () => {
  assert.equal(getScanConcurrency({}), 2)
  assert.equal(getScanConcurrency({ hardwareConcurrency: 4, deviceMemory: 4 }), 2)
  assert.equal(getScanConcurrency({ hardwareConcurrency: 2, deviceMemory: 8 }), 2)
  assert.equal(getScanConcurrency({ hardwareConcurrency: 1, deviceMemory: 8 }), 1)
  assert.equal(getScanConcurrency({ hardwareConcurrency: 8, deviceMemory: 2 }), 1)
  assert.equal(getScanConcurrency({ hardwareConcurrency: 8, deviceMemory: 8 }), 5)
  assert.equal(getScanConcurrency({ hardwareConcurrency: 14, deviceMemory: 36 }), 28)
})

test('scan pool runs at most two tasks, ignores wrong ids and reuses workers', async (context) => {
  const clock = installFakeRuntime(context)
  const scans = [0, 1, 2, 3].map(index => scanPhoto(fakeFile(`scan-${index}.jpg`)))

  assert.equal(FakeWorker.instances.length, 2)
  assert.equal(FakeWorker.active, 2)
  assert.equal(getActiveScanCount(), 2)
  assert.equal(clock.timers.length, 2)
  const first = FakeWorker.instances[0]!
  first.wrongId()
  assert.equal(first.current?.file.name, 'scan-0.jpg')
  assert.equal(clock.timers.length, 2)

  first.succeed('7000000001')
  assert.equal(first.current?.file.name, 'scan-2.jpg')
  assert.equal(clock.timers.length, 3)
  FakeWorker.instances[1]!.succeed('7000000002')
  assert.equal(FakeWorker.instances[1]!.current?.file.name, 'scan-3.jpg')
  first.succeed('7000000003')
  FakeWorker.instances[1]!.succeed('7000000004')

  const results = await Promise.all(scans)
  assert.deepEqual(results.map(result => result.certIds[0]), [
    '7000000001', '7000000002', '7000000003', '7000000004',
  ])
  assert.equal(FakeWorker.instances.length, 2)
  assert.equal(FakeWorker.maxActive, 2)
})

test('a low-resource pool keeps its second task queued without starting its timer', async (context) => {
  const clock = installFakeRuntime(context, { hardwareConcurrency: 1, deviceMemory: 8 })
  const first = scanPhoto(fakeFile('first.jpg'))
  const second = scanPhoto(fakeFile('second.jpg'))
  assert.equal(FakeWorker.instances.length, 1)
  assert.equal(clock.timers.length, 1)
  assert.equal(FakeWorker.instances[0]!.current?.file.name, 'first.jpg')

  FakeWorker.instances[0]!.succeed()
  assert.equal(clock.timers.length, 2)
  assert.equal(FakeWorker.instances[0]!.current?.file.name, 'second.jpg')
  FakeWorker.instances[0]!.succeed()
  await Promise.all([first, second])
})

test('timeout, crash and postMessage failure recycle only their own slots', async (context) => {
  const clock = installFakeRuntime(context)
  FakeWorker.throwOnPost.add('post.jpg')
  const timedOut = scanPhoto(fakeFile('timeout.jpg'))
  const crashed = scanPhoto(fakeFile('crash.jpg'))
  const steadyAfterTimeout = scanPhoto(fakeFile('steady-timeout.jpg'))
  const steadyAfterCrash = scanPhoto(fakeFile('steady-crash.jpg'))
  const postFailure = scanPhoto(fakeFile('post.jpg'))
  const timeoutRejected = assert.rejects(timedOut, /二维码识别超时/)
  const crashRejected = assert.rejects(crashed, /意外停止/)
  const postRejected = assert.rejects(postFailure, /无法启动/)

  clock.fire(0)
  assert.equal(FakeWorker.instances[0]!.terminated, true)
  assert.equal(FakeWorker.running().some(worker => worker.current?.file.name === 'crash.jpg'), true)

  const crashWorker = FakeWorker.running().find(worker => worker.current?.file.name === 'crash.jpg')!
  crashWorker.crash()
  assert.equal(crashWorker.terminated, true)

  const timeoutRecovery = FakeWorker.running().find(worker => worker.current?.file.name === 'steady-timeout.jpg')!
  timeoutRecovery.succeed()
  const crashRecovery = FakeWorker.running().find(worker => worker.current?.file.name === 'steady-crash.jpg')!
  crashRecovery.succeed()

  await Promise.all([
    timeoutRejected,
    crashRejected,
    postRejected,
    steadyAfterTimeout,
    steadyAfterCrash,
  ])
  assert.ok(FakeWorker.maxActive <= 2)
})

test('cancel rejects running and queued scans without reviving the queue, then a new scan recovers', async (context) => {
  installFakeRuntime(context)
  const runningA = scanPhoto(fakeFile('running-a.jpg'))
  const runningB = scanPhoto(fakeFile('running-b.jpg'))
  const queued = scanPhoto(fakeFile('queued.jpg'))
  const rejected = [runningA, runningB, queued].map(scan => assert.rejects(scan, isAbortError))
  const oldWorker = FakeWorker.instances[0]!
  const oldId = oldWorker.current!.id

  cancelScan()
  assert.equal(FakeWorker.instances.length, 2)
  assert.ok(FakeWorker.instances.every(worker => worker.terminated))
  assert.equal(FakeWorker.active, 0)
  assert.equal(getActiveScanCount(), 0)
  await Promise.all(rejected)
  assert.equal(FakeWorker.instances.length, 2)

  const recovered = scanPhoto(fakeFile('recovered.jpg'))
  assert.equal(FakeWorker.instances.length, 3)
  // Even a host-delivered callback after termination belongs to the old run.
  oldWorker.onmessage?.({ data: { id: oldId, certIds: ['stale'], qrTexts: [] } } as MessageEvent)
  assert.equal(FakeWorker.instances[2]!.current?.file.name, 'recovered.jpg')
  FakeWorker.instances[2]!.succeed('7999999999')
  assert.deepEqual((await recovered).certIds, ['7999999999'])
})

test('desktop-class scan pool runs fourteen independent jobs and preserves caller order', async (context) => {
  installFakeRuntime(context, { hardwareConcurrency: 14, deviceMemory: 36 })
  const jobs = Array.from({ length: 28 }, (_, i) => scanPhoto(fakeFile(`parallel-${i}.jpg`), 'deep'))
  assert.equal(FakeWorker.active, 14)
  while (FakeWorker.running().length) {
    const worker = FakeWorker.running().at(-1)!
    const index = /parallel-(\d+)/.exec(worker.current!.file.name)![1]
    worker.succeed(`700000${index}`)
  }
  assert.equal(FakeWorker.maxActive, 14)
  assert.deepEqual((await Promise.all(jobs)).map(job => job.certIds[0]), Array.from({ length: 28 }, (_, i) => `700000${i}`))
})

test('healthy high-memory scan work can grow above fourteen and cancellation drains the expanded pool', async (context) => {
  installFakeRuntime(context, { hardwareConcurrency: 14, deviceMemory: 36 })
  const performanceDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'performance')
  let tick = 0
  Object.defineProperty(globalThis, 'performance', {
    configurable: true,
    value: { now: () => ++tick },
  })
  context.after(() => restoreGlobal('performance', performanceDescriptor))

  const jobs = Array.from({ length: 80 }, (_, index) =>
    scanPhoto(fakeFile(`growth-${index}.jpg`), 'deep'),
  )
  const outcomes = jobs.map(job => job.then(
    () => 'fulfilled' as const,
    error => {
      if (isAbortError(error)) return 'aborted' as const
      throw error
    },
  ))
  assert.equal(getActiveScanCount(), 14)

  let completions = 0
  while (FakeWorker.maxActive <= 14 && completions < 50) {
    FakeWorker.running()[0]!.succeed(`700001${completions}`)
    completions += 1
  }
  assert.ok(FakeWorker.maxActive > 14 && FakeWorker.maxActive <= 28)
  assert.equal(getActiveScanCount(), FakeWorker.active)

  cancelScan()
  assert.equal(getActiveScanCount(), 0)
  assert.equal(FakeWorker.active, 0)
  const settled = await Promise.all(outcomes)
  assert.ok(settled.includes('fulfilled'))
  assert.ok(settled.includes('aborted'))
})
