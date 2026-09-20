import {
  createAdaptiveGovernor,
  estimateTaskFootprint,
  getProcessingConcurrency,
  getScanSearchCeiling,
  type AdaptiveConcurrencyGovernor,
} from './performance-policy'
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
let governor: AdaptiveConcurrencyGovernor | undefined

/** Maximum QR search range; the live pool starts at the normal static budget. */
export function getScanConcurrency(capabilities?: ScanDeviceCapabilities): number {
  return getScanSearchCeiling(capabilities)
}

/** Current active-task limit after live resource and throughput observations. */
export function getActualScanConcurrency(): number {
  return getGovernor().getConcurrency()
}

/** Number of scan workers currently executing a task; queued work is excluded. */
export function getActiveScanCount(): number {
  return workerSlots.reduce((count, slot) => count + (slot.task ? 1 : 0), 0)
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
      settleTask(task, { ok: false, error }, false)
    }
  }
  governor = undefined
}

function pumpQueue(generation: number): void {
  if (generation !== poolGeneration || pumping) return
  pumping = true

  try {
    const adaptive = getGovernor()
    void adaptive.refreshRuntimeMetrics()
    trimIdleSlots(adaptive.getConcurrency())
    while (generation === poolGeneration && queuedScans.length > 0) {
      const concurrency = adaptive.getConcurrency()
      const active = workerSlots.reduce((count, slot) => count + (slot.task ? 1 : 0), 0)
      if (active >= concurrency) break
      let slot = workerSlots.find(candidate => candidate.generation === generation && !candidate.task)
      if (!slot) {
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
    governor?.taskFinished(task.id)
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
      governor?.taskCancelled(task.id)
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
  getGovernor().taskStarted(task.id, estimateTaskFootprint('scan', task.file.size))
  task.timer = setTimeout(() => {
    if (task.state !== 'running' || slot.task !== task) return
    recycleSlot(slot)
    governor?.taskFinished(task.id)
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
    governor?.taskCancelled(task.id)
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

function trimIdleSlots(concurrency: number): void {
  const active = workerSlots.reduce((count, slot) => count + (slot.task ? 1 : 0), 0)
  let idleAllowance = Math.max(0, concurrency - active)
  for (let index = workerSlots.length - 1; index >= 0; index -= 1) {
    const slot = workerSlots[index]
    if (!slot || slot.task) continue
    if (idleAllowance > 0) {
      idleAllowance -= 1
      continue
    }
    recycleSlot(slot)
  }
}

function getGovernor(): AdaptiveConcurrencyGovernor {
  const maximum = getScanConcurrency()
  if (!governor || governor.maxConcurrency !== maximum) {
    governor = createAdaptiveGovernor('scan', maximum, {
      initialConcurrency: getProcessingConcurrency('scan'),
    })
  }
  return governor
}

function terminateWorker(worker: Worker): void {
  try {
    worker.terminate()
  } catch {
    // Pool state must still be released when host cleanup fails.
  }
}

function chineseMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error && /[\u3400-\u9fff]/.test(cause.message) ? cause.message : fallback
}
