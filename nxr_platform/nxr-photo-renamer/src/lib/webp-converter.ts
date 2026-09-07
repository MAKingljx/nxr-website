export interface LosslessWebpOptions {
  signal?: AbortSignal
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

const CONVERSION_TIMEOUT_MS = 120_000
let nextId = 1
let conversionQueue: Promise<void> = Promise.resolve()

/**
 * Convert one local image to a verified, pixel-lossless WebP in an isolated worker.
 * Calls are serialized so multiple large photos cannot multiply peak WASM memory.
 */
export function convertToLosslessWebp(
  file: File,
  options: LosslessWebpOptions = {},
): Promise<Blob> {
  const conversion = conversionQueue.then(() => runConversion(file, options.signal))
  conversionQueue = conversion.then(
    () => undefined,
    () => undefined,
  )
  return conversion
}

function runConversion(file: File, signal?: AbortSignal): Promise<Blob> {
  if (signal?.aborted) return Promise.reject(abortError())

  const worker = new Worker(new URL('../webp.worker.ts', import.meta.url), { type: 'module' })
  const id = nextId++

  return new Promise<Blob>((resolve, reject) => {
    let settled = false

    const finish = (action: () => void) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      signal?.removeEventListener('abort', onAbort)
      worker.terminate()
      action()
    }

    const onAbort = () => finish(() => reject(abortError()))
    const timer = setTimeout(() => {
      finish(() => reject(new Error('无损 WebP 转换超过 120 秒，已停止处理以释放内存。')))
    }, CONVERSION_TIMEOUT_MS)

    worker.onmessage = (event: MessageEvent<WorkerResponse>) => {
      const response = event.data
      if (!response || response.id !== id) return
      if (!response.ok) {
        finish(() => reject(new Error(response.error)))
        return
      }
      finish(() => resolve(new Blob([response.buffer], { type: 'image/webp' })))
    }

    worker.onerror = () => {
      finish(() => reject(new Error('本地无损 WebP 转换程序意外停止。')))
    }

    signal?.addEventListener('abort', onAbort, { once: true })
    try {
      worker.postMessage({ id, file })
    } catch {
      finish(() => reject(new Error('无法启动本地无损 WebP 转换。')))
    }
  })
}

function abortError(): DOMException {
  return new DOMException('无损 WebP 转换已取消。', 'AbortError')
}
