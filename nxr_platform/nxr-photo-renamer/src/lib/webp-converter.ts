import {
  createAdaptiveGovernor,
  estimateTaskFootprint,
  getProcessingConcurrency,
  type AdaptiveConcurrencyGovernor,
  type DeviceCapabilities,
} from './performance-policy'

export interface WebpOptions {
  signal?: AbortSignal
  quality?: number
}

export interface LosslessWebpOptions {
  signal?: AbortSignal
}

export type WebpDeviceCapabilities = DeviceCapabilities

type ConversionMode = 'quality' | 'lossless'

interface WorkerSuccess {
  id: number
  ok: true
  buffer: ArrayBuffer
}

interface WorkerFailure {
  id: number
  ok: false
  error: string
}

type WorkerResponse = WorkerSuccess | WorkerFailure

interface ConversionTask {
  id: number
  file: File
  mode: ConversionMode
  quality?: number
  signal?: AbortSignal
  state: 'queued' | 'running' | 'settled'
  resolve: (blob: Blob) => void
  reject: (error: unknown) => void
  abort: () => void
  slot?: WorkerSlot
}

interface WorkerSlot {
  worker: Worker
  task: ConversionTask | null
}

type ConversionResult =
  | { ok: true; blob: Blob }
  | { ok: false; error: unknown }

const DEFAULT_WEBP_QUALITY = 0.92
const pendingConversions: ConversionTask[] = []
const workerSlots: WorkerSlot[] = []
let nextId = 1
let pumping = false
let governor: AdaptiveConcurrencyGovernor | undefined

/** Preserve every reported CPU lane as the pool's static maximum budget. */
export function getWebpConcurrency(capabilities?: WebpDeviceCapabilities): number {
  return getProcessingConcurrency('webp', capabilities)
}

/** Current active-task limit after live resource and throughput observations. */
export function getActualWebpConcurrency(): number {
  return getGovernor().getConcurrency()
}

/** Number of WebP workers currently executing a task; queued work is excluded. */
export function getActiveWebpCount(): number {
  return workerSlots.reduce((count, slot) => count + (slot.task ? 1 : 0), 0)
}

/** Convert to original-size high-quality WebP. Existing valid static WebP is kept byte-for-byte. */
export function convertToWebp(file: File, options: WebpOptions = {}): Promise<Blob> {
  const quality = options.quality ?? DEFAULT_WEBP_QUALITY
  if (!Number.isFinite(quality) || quality <= 0 || quality > 1) {
    return Promise.reject(new TypeError('WebP 质量必须大于 0 且不超过 1。'))
  }
  return enqueueConversion(file, 'quality', options.signal, quality)
}

/** Compatibility path for callers that still require verified pixel-lossless WebP. */
export function convertToLosslessWebp(
  file: File,
  options: LosslessWebpOptions = {},
): Promise<Blob> {
  return enqueueConversion(file, 'lossless', options.signal)
}

/** Release persistent idle workers; running and queued conversions are cancelled. */
export function disposeWebpWorkers(): void {
  const error = abortError()
  const queued = pendingConversions.splice(0)
  for (const task of queued) {
    governor?.taskCancelled(task.id)
    settleTask(task, { ok: false, error }, false)
  }

  const slots = workerSlots.splice(0)
  for (const slot of slots) {
    const task = slot.task
    slot.task = null
    terminateWorker(slot.worker)
    if (task) {
      governor?.taskCancelled(task.id)
      task.slot = undefined
      settleTask(task, { ok: false, error }, false)
    }
  }
  governor = undefined
}

function enqueueConversion(
  file: File,
  mode: ConversionMode,
  signal?: AbortSignal,
  quality?: number,
): Promise<Blob> {
  if (signal?.aborted) return Promise.reject(abortError())

  return new Promise<Blob>((resolve, reject) => {
    const task: ConversionTask = {
      id: nextId++,
      file,
      mode,
      quality,
      signal,
      state: 'queued',
      resolve,
      reject,
      abort: () => abortTask(task),
    }
    signal?.addEventListener('abort', task.abort, { once: true })
    pendingConversions.push(task)
    pumpQueue()
  })
}

function pumpQueue(): void {
  if (pumping) return
  pumping = true

  try {
    const adaptive = getGovernor()
    void adaptive.refreshRuntimeMetrics()
    trimIdleSlots(adaptive.getConcurrency())
    while (pendingConversions.length > 0) {
      const concurrency = adaptive.getConcurrency()
      const active = workerSlots.reduce((count, slot) => count + (slot.task ? 1 : 0), 0)
      if (active >= concurrency) break
      let slot = workerSlots.find(candidate => candidate.task === null)
      if (!slot) {
        try {
          slot = createWorkerSlot()
          workerSlots.push(slot)
        } catch {
          const failed = pendingConversions.shift()
          if (failed) {
            settleTask(failed, {
              ok: false,
              error: new Error('无法启动本地 WebP 转换。'),
            }, false)
          }
          continue
        }
      }

      const task = pendingConversions.shift()
      if (!task || task.state !== 'queued') continue
      if (task.signal?.aborted) {
        settleTask(task, { ok: false, error: abortError() }, false)
        continue
      }
      startTask(slot, task)
    }
  } finally {
    pumping = false
  }
}

function createWorkerSlot(): WorkerSlot {
  const slot: WorkerSlot = {
    worker: new Worker(new URL('../webp.worker.ts', import.meta.url), { type: 'module' }),
    task: null,
  }

  slot.worker.onmessage = (event: MessageEvent<WorkerResponse>) => {
    const task = slot.task
    const response = event.data
    if (!task || !response || response.id !== task.id) return

    slot.task = null
    task.slot = undefined
    governor?.taskFinished(task.id)
    if (!response.ok) {
      settleTask(task, { ok: false, error: new Error(response.error) })
      return
    }
    settleTask(task, {
      ok: true,
      blob: new Blob([response.buffer], { type: 'image/webp' }),
    })
  }

  const failWorker = () => {
    const task = slot.task
    destroySlot(slot)
    if (task) {
      governor?.taskCancelled(task.id)
      task.slot = undefined
      settleTask(task, { ok: false, error: new Error('本地 WebP 转换程序意外停止。') })
    } else {
      pumpQueue()
    }
  }
  slot.worker.onerror = failWorker
  slot.worker.onmessageerror = failWorker
  return slot
}

function startTask(slot: WorkerSlot, task: ConversionTask): void {
  task.state = 'running'
  task.slot = slot
  slot.task = task
  getGovernor().taskStarted(task.id, estimateTaskFootprint('webp', task.file.size))
  try {
    slot.worker.postMessage({
      id: task.id,
      file: task.file,
      mode: task.mode,
      ...(task.quality === undefined ? {} : { quality: task.quality }),
    })
  } catch {
    destroySlot(slot)
    governor?.taskCancelled(task.id)
    task.slot = undefined
    settleTask(task, { ok: false, error: new Error('无法启动本地 WebP 转换。') })
  }
}

function abortTask(task: ConversionTask): void {
  if (task.state === 'settled') return
  if (task.state === 'running' && task.slot) {
    destroySlot(task.slot)
    governor?.taskCancelled(task.id)
    task.slot = undefined
  }
  settleTask(task, { ok: false, error: abortError() })
}

function settleTask(
  task: ConversionTask,
  result: ConversionResult,
  continuePumping = true,
): void {
  if (task.state === 'settled') return
  const wasQueued = task.state === 'queued'
  task.state = 'settled'
  task.signal?.removeEventListener('abort', task.abort)

  if (wasQueued) {
    const index = pendingConversions.indexOf(task)
    if (index >= 0) pendingConversions.splice(index, 1)
  }
  if (result.ok) task.resolve(result.blob)
  else task.reject(result.error)
  if (continuePumping) pumpQueue()
}

function trimIdleSlots(concurrency: number): void {
  const active = workerSlots.reduce((count, slot) => count + (slot.task ? 1 : 0), 0)
  let idleAllowance = Math.max(0, concurrency - active)
  for (let index = workerSlots.length - 1; index >= 0; index -= 1) {
    const slot = workerSlots[index]
    if (slot?.task) continue
    if (idleAllowance > 0) {
      idleAllowance -= 1
      continue
    }
    destroySlot(slot)
  }
}

function getGovernor(): AdaptiveConcurrencyGovernor {
  const maximum = getWebpConcurrency()
  if (!governor || governor.maxConcurrency !== maximum) {
    governor = createAdaptiveGovernor('webp', maximum)
  }
  return governor
}

function destroySlot(slot: WorkerSlot): void {
  const index = workerSlots.indexOf(slot)
  if (index >= 0) workerSlots.splice(index, 1)
  slot.task = null
  terminateWorker(slot.worker)
}

function terminateWorker(worker: Worker): void {
  try {
    worker.terminate()
  } catch {
    // The slot is removed even if browser cleanup itself reports an error.
  }
}

function abortError(): DOMException {
  return new DOMException('WebP 转换已取消。', 'AbortError')
}
