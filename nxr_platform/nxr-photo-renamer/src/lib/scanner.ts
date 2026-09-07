import { SCAN_LIMITS, type ScanMode } from './scan-policy'

export interface ScanResult {
  certIds: string[]
  qrTexts: string[]
  error?: string
}

export interface ScanDeviceCapabilities {
  hardwareConcurrency?: number
  deviceMemory?: number
}

interface WorkerResult extends ScanResult {
  id: number
}

interface ScanTask {
  id: number
  file: File
  mode: ScanMode
  generation: number
  state: 'queued' | 'running' | 'settled'
  resolve: (result: ScanResult) => void
  reject: (reason: unknown) => void
  timer?: ReturnType<typeof setTimeout>
  slot?: WorkerSlot
}

interface WorkerSlot {
  worker: Worker
  generation: number
  task: ScanTask | null
}

const queuedScans: ScanTask[] = []
const workerSlots: WorkerSlot[] = []
let nextId = 1
let poolGeneration = 0
let pumping = false

/** Keep scanning conservative on low-resource devices and use at most two workers elsewhere. */
export function getScanConcurrency(
  capabilities: ScanDeviceCapabilities = currentDeviceCapabilities(),
): 1 | 2 {
  const cores = positiveCapability(capabilities.hardwareConcurrency)
  const memory = positiveCapability(capabilities.deviceMemory)
  if (cores !== undefined && cores < 4) return 1
  if (memory !== undefined && memory < 4) return 1
  return 2
}

export function scanPhoto(file: File, mode: ScanMode = 'standard'): Promise<ScanResult> {
  return new Promise<ScanResult>((resolve, reject) => {
    queuedScans.push({
      id: nextId++,
      file,
      mode,
      generation: poolGeneration,
      state: 'queued',
      resolve,
      reject,
    })
    pumpQueue(poolGeneration)
  })
}

export function cancelScan(): void {
  poolGeneration += 1
  const error = new DOMException('二维码识别已取消。', 'AbortError')

  const queued = queuedScans.splice(0)
  for (const task of queued) settleTask(task, { ok: false, error }, false)

  const slots = workerSlots.splice(0)
  for (const slot of slots) {
    const task = slot.task
    slot.task = null
    terminateWorker(slot.worker)
    if (task) settleTask(task, { ok: false, error }, false)
  }
}

function pumpQueue(generation: number): void {
  if (generation !== poolGeneration || pumping) return
  pumping = true

  try {
    const concurrency = getScanConcurrency()
    while (generation === poolGeneration && queuedScans.length > 0) {
      let slot = workerSlots.find(candidate => candidate.generation === generation && !candidate.task)
      if (!slot) {
        if (workerSlots.length >= concurrency) break
        try {
          slot = createWorkerSlot(generation)
          workerSlots.push(slot)
        } catch {
          const failed = queuedScans.shift()
          if (failed) {
            settleTask(failed, {
              ok: false,
              error: new Error('无法启动本地二维码识别。'),
            }, false)
          }
          continue
        }
      }

      const task = queuedScans.shift()
      if (!task || task.state !== 'queued' || task.generation !== generation) continue
      startTask(slot, task)
    }
  } finally {
    pumping = false
  }
}

function createWorkerSlot(generation: number): WorkerSlot {
  const worker = new Worker(new URL('../qr.worker.ts', import.meta.url), { type: 'module' })
  const slot: WorkerSlot = { worker, generation, task: null }

  worker.onmessage = (event: MessageEvent<WorkerResult>) => {
    if (slot.generation !== poolGeneration) return
    const task = slot.task
    const response = event.data
    if (!task || !response || response.id !== task.id) return

    slot.task = null
    settleTask(task, {
      ok: true,
      result: {
        certIds: response.certIds,
        qrTexts: response.qrTexts,
        ...(response.error ? { error: response.error } : {}),
      },
    })
  }
  worker.onerror = () => {
    if (slot.generation !== poolGeneration) return
    const task = slot.task
    recycleSlot(slot)
    if (task) {
      settleTask(task, {
        ok: false,
        error: new Error('本地二维码识别程序意外停止。'),
      })
    }
  }
  return slot
}

function startTask(slot: WorkerSlot, task: ScanTask): void {
  task.state = 'running'
  task.slot = slot
  slot.task = task
  task.timer = setTimeout(() => {
    if (task.state !== 'running' || slot.task !== task) return
    recycleSlot(slot)
    settleTask(task, {
      ok: false,
      error: new Error(task.mode === 'deep'
        ? '深度补扫超时。请检查二维码是否模糊、反光或被遮挡。'
        : '二维码识别超时，将自动深度补扫。'),
    })
  }, SCAN_LIMITS[task.mode].timeoutMs)

  try {
    slot.worker.postMessage({ id: task.id, file: task.file, mode: task.mode })
  } catch (cause) {
    recycleSlot(slot)
    settleTask(task, {
      ok: false,
      error: new Error(chineseMessage(cause, '无法启动本地二维码识别。')),
    })
  }
}

type TaskResult =
  | { ok: true; result: ScanResult }
  | { ok: false; error: unknown }

function settleTask(task: ScanTask, result: TaskResult, pump = true): void {
  if (task.state === 'settled') return
  const previousState = task.state
  task.state = 'settled'
  if (task.timer !== undefined) clearTimeout(task.timer)
  task.timer = undefined

  if (previousState === 'queued') {
    const index = queuedScans.indexOf(task)
    if (index >= 0) queuedScans.splice(index, 1)
  } else if (task.slot?.task === task) {
    task.slot.task = null
  }
  task.slot = undefined

  if (result.ok) task.resolve(result.result)
  else task.reject(result.error)
  if (pump) pumpQueue(task.generation)
}

function recycleSlot(slot: WorkerSlot): void {
  const index = workerSlots.indexOf(slot)
  if (index >= 0) workerSlots.splice(index, 1)
  slot.task = null
  terminateWorker(slot.worker)
}

function terminateWorker(worker: Worker): void {
  try {
    worker.terminate()
  } catch {
    // Pool state must still be released when host cleanup fails.
  }
}

function currentDeviceCapabilities(): ScanDeviceCapabilities {
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

function chineseMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error && /[\u3400-\u9fff]/.test(cause.message) ? cause.message : fallback
}
