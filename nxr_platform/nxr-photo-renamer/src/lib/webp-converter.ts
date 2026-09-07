export interface LosslessWebpOptions {
  signal?: AbortSignal
}

export interface WebpDeviceCapabilities {
  hardwareConcurrency?: number
  deviceMemory?: number
}

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
  file: File
  signal?: AbortSignal
  state: 'queued' | 'running' | 'settled'
  resolve: (blob: Blob) => void
  reject: (error: unknown) => void
  abort: () => void
  cancelWorker?: () => void
}

type ConversionResult =
  | { ok: true; blob: Blob }
  | { ok: false; error: unknown }

const pendingConversions: ConversionTask[] = []
let nextId = 1
let activeConversions = 0
let pumping = false

/**
 * Keep one worker on low-resource devices and use at most two elsewhere.
 * Missing capability values use the normal two-worker default.
 */
export function getWebpConcurrency(
  capabilities: WebpDeviceCapabilities = currentDeviceCapabilities(),
): 1 | 2 {
  const cores = positiveCapability(capabilities.hardwareConcurrency)
  const memory = positiveCapability(capabilities.deviceMemory)

  if (cores !== undefined && cores < 4) return 1
  if (memory !== undefined && memory < 4) return 1
  return 2
}

/**
 * Convert one local image to a verified, pixel-lossless WebP in an isolated worker.
 * Calls share a bounded worker pool so batches can make progress without unbounded
 * WASM memory growth.
 */
export function convertToLosslessWebp(
  file: File,
  options: LosslessWebpOptions = {},
): Promise<Blob> {
  if (options.signal?.aborted) return Promise.reject(abortError())

  return new Promise<Blob>((resolve, reject) => {
    const task: ConversionTask = {
      file,
      signal: options.signal,
      state: 'queued',
      resolve,
      reject,
      abort: () => abortTask(task),
    }

    options.signal?.addEventListener('abort', task.abort, { once: true })
    pendingConversions.push(task)
    pumpQueue()
  })
}

function pumpQueue(): void {
  if (pumping) return
  pumping = true

  try {
    const concurrency = getWebpConcurrency()
    while (activeConversions < concurrency && pendingConversions.length > 0) {
      const task = pendingConversions.shift()
      if (!task || task.state !== 'queued') continue

      if (task.signal?.aborted) {
        settleTask(task, { ok: false, error: abortError() })
        continue
      }

      startTask(task)
    }
  } finally {
    pumping = false
  }
}

function startTask(task: ConversionTask): void {
  task.state = 'running'
  activeConversions += 1

  let worker: Worker
  try {
    worker = new Worker(new URL('../webp.worker.ts', import.meta.url), { type: 'module' })
  } catch {
    settleTask(task, { ok: false, error: new Error('无法启动本地无损 WebP 转换。') })
    return
  }

  const id = nextId++
  const finish = (result: ConversionResult) => {
    if (task.state !== 'running') return
    try {
      worker.terminate()
    } catch {
      // Release the pool slot even if worker cleanup itself fails.
    }
    task.cancelWorker = undefined
    settleTask(task, result)
  }

  task.cancelWorker = () => finish({ ok: false, error: abortError() })

  worker.onmessage = (event: MessageEvent<WorkerResponse>) => {
    const response = event.data
    if (!response || response.id !== id) return
    if (!response.ok) {
      finish({ ok: false, error: new Error(response.error) })
      return
    }
    finish({
      ok: true,
      blob: new Blob([response.buffer], { type: 'image/webp' }),
    })
  }

  worker.onerror = () => {
    finish({ ok: false, error: new Error('本地无损 WebP 转换程序意外停止。') })
  }

  try {
    worker.postMessage({ id, file: task.file })
  } catch {
    finish({ ok: false, error: new Error('无法启动本地无损 WebP 转换。') })
  }
}

function abortTask(task: ConversionTask): void {
  if (task.state === 'settled') return
  if (task.state === 'running') {
    task.cancelWorker?.()
    return
  }
  settleTask(task, { ok: false, error: abortError() })
}

function settleTask(task: ConversionTask, result: ConversionResult): void {
  if (task.state === 'settled') return

  const previousState = task.state
  task.state = 'settled'
  task.signal?.removeEventListener('abort', task.abort)

  if (previousState === 'queued') {
    const index = pendingConversions.indexOf(task)
    if (index >= 0) pendingConversions.splice(index, 1)
  } else {
    activeConversions -= 1
  }

  if (result.ok) task.resolve(result.blob)
  else task.reject(result.error)
  pumpQueue()
}

function currentDeviceCapabilities(): WebpDeviceCapabilities {
  if (typeof navigator === 'undefined') return {}
  const browserNavigator = navigator as Navigator & { deviceMemory?: number }
  return {
    hardwareConcurrency: browserNavigator.hardwareConcurrency,
    deviceMemory: browserNavigator.deviceMemory,
  }
}

function positiveCapability(value: number | undefined): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) && value > 0
    ? value
    : undefined
}

function abortError(): DOMException {
  return new DOMException('无损 WebP 转换已取消。', 'AbortError')
}
