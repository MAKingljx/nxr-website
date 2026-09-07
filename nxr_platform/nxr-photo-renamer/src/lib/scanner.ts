import { SCAN_LIMITS, type ScanMode } from './scan-policy'

export interface ScanResult {
  certIds: string[]
  qrTexts: string[]
  error?: string
}

interface WorkerResult extends ScanResult {
  id: number
}

interface PendingScan {
  resolve: (result: ScanResult) => void
  reject: (reason: Error) => void
  timer: ReturnType<typeof setTimeout>
}

let worker: Worker | null = null
let nextId = 1
const pending = new Map<number, PendingScan>()

export function scanPhoto(file: File, mode: ScanMode = 'standard'): Promise<ScanResult> {
  const activeWorker = ensureWorker()
  const id = nextId++

  return new Promise<ScanResult>((resolve, reject) => {
    const timer = setTimeout(() => {
      const request = pending.get(id)
      if (!request) return
      failPending(new Error(mode === 'deep'
        ? '深度补扫超时。请检查二维码是否模糊、反光或被遮挡。'
        : '二维码识别超时，将自动深度补扫。'))
    }, SCAN_LIMITS[mode].timeoutMs)

    pending.set(id, { resolve, reject, timer })
    try {
      activeWorker.postMessage({ id, file, mode })
    } catch (cause) {
      clearTimeout(timer)
      pending.delete(id)
      reject(new Error(chineseMessage(cause, '无法启动本地二维码识别。')))
    }
  })
}

export function cancelScan(): void {
  if (worker) worker.terminate()
  worker = null
  const error = new DOMException('二维码识别已取消。', 'AbortError')
  for (const request of pending.values()) {
    clearTimeout(request.timer)
    request.reject(error)
  }
  pending.clear()
}

function ensureWorker(): Worker {
  if (worker) return worker
  worker = new Worker(new URL('../qr.worker.ts', import.meta.url), { type: 'module' })
  worker.onmessage = (event: MessageEvent<WorkerResult>) => {
    const request = pending.get(event.data.id)
    if (!request) return
    pending.delete(event.data.id)
    clearTimeout(request.timer)
    request.resolve({
      certIds: event.data.certIds,
      qrTexts: event.data.qrTexts,
      ...(event.data.error ? { error: event.data.error } : {}),
    })
  }
  worker.onerror = () => {
    failPending(new Error('本地二维码识别程序意外停止。'))
  }
  return worker
}

function failPending(error: Error) {
  for (const request of pending.values()) {
    clearTimeout(request.timer)
    request.reject(error)
  }
  pending.clear()
  restartWorker()
}

function restartWorker() {
  if (worker) worker.terminate()
  worker = null
}

function chineseMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error && /[\u3400-\u9fff]/.test(cause.message) ? cause.message : fallback
}
