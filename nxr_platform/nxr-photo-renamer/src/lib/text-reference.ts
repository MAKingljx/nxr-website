import { get, set } from 'idb-keyval'
import { createWorker, OEM, PSM } from 'tesseract.js'
import type { TextReference } from './types'
import { classifyTextReference } from './text-reference-policy'

const OCR_TIMEOUT_MS = 20_000
const MODEL_CACHE_PATH = 'nxr-photo-renamer-ocr-v1'
const MODEL_CACHE_KEY = `${MODEL_CACHE_PATH}/eng.traineddata`
const CANCELLED_MESSAGE = '文字参考识别已取消。'

let worker: Tesseract.Worker | null = null
let workerPromise: Promise<Tesseract.Worker> | null = null
let queue: Promise<void> = Promise.resolve()
let generation = 0
let workerEpoch = 0
const pending = new Set<(reason: Error) => void>()

export function scanTextReference(file: File, qrCertId?: string): Promise<TextReference> {
  const requestGeneration = generation
  return new Promise<TextReference>((resolve, reject) => {
    let settled = false
    const rejectPending = (reason: Error) => {
      if (settled) return
      settled = true
      reject(reason)
    }
    pending.add(rejectPending)

    const job = queue.then(async () => {
      if (requestGeneration !== generation) throw cancelledError()
      try {
        return await withTimeout(
          recognizeTopLabel(file, qrCertId, requestGeneration),
          requestGeneration,
        )
      } catch (error) {
        if (isCancelled(error) || requestGeneration !== generation) throw cancelledError()
        return {
          state: 'unavailable',
          candidates: [],
          rawText: '',
          error: error instanceof Error ? error.message : String(error),
        } satisfies TextReference
      }
    })
    queue = job.then(() => undefined, () => undefined)
    job.then((result) => {
      if (settled) return
      settled = true
      resolve(result)
    }, rejectPending).finally(() => pending.delete(rejectPending))
  })
}

export function cancelTextReference(): void {
  generation += 1
  const reason = cancelledError()
  for (const reject of pending) reject(reason)
  pending.clear()
  queue = Promise.resolve()
  void resetWorker()
}

export function topLabelRegion(width: number, height: number) {
  return {
    left: Math.floor(width * 0.05),
    top: Math.floor(height * 0.03),
    width: Math.max(1, Math.ceil(width * 0.9)),
    height: Math.max(1, Math.ceil(height * 0.31)),
  }
}

async function recognizeTopLabel(
  file: File,
  qrCertId: string | undefined,
  requestGeneration: number,
): Promise<TextReference> {
  const canvas = await prepareTopLabel(file)
  if (requestGeneration !== generation) throw cancelledError()
  const activeWorker = await getWorker(requestGeneration)
  const result = await activeWorker.recognize(canvas)
  if (requestGeneration !== generation) throw cancelledError()
  return classifyTextReference(result.data.text, qrCertId)
}

async function prepareTopLabel(file: File): Promise<HTMLCanvasElement> {
  if (file.size === 0) throw new Error('图片文件为空。')
  if (file.size > 40 * 1024 * 1024) throw new Error('图片超过 40 MB 的文字识别上限。')
  const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' })
  try {
    if (bitmap.width <= 0 || bitmap.height <= 0) throw new Error('图片尺寸无效。')
    if (bitmap.width * bitmap.height > 120_000_000) {
      throw new Error('图片超过 1.2 亿像素的文字识别上限。')
    }
    const region = topLabelRegion(bitmap.width, bitmap.height)
    const targetWidth = Math.min(2200, Math.max(1400, region.width))
    const targetHeight = Math.max(1, Math.round(region.height * targetWidth / region.width))
    const canvas = document.createElement('canvas')
    canvas.width = targetWidth
    canvas.height = targetHeight
    const context = canvas.getContext('2d', { willReadFrequently: false })
    if (!context) throw new Error('无法创建文字识别画布。')
    context.filter = 'grayscale(1) contrast(1.6)'
    context.drawImage(
      bitmap,
      region.left,
      region.top,
      region.width,
      region.height,
      0,
      0,
      targetWidth,
      targetHeight,
    )
    return canvas
  } finally {
    bitmap.close()
  }
}

async function getWorker(requestGeneration: number): Promise<Tesseract.Worker> {
  if (worker) return worker
  if (!workerPromise) {
    const ownerEpoch = workerEpoch
    const creating = createLocalWorker(requestGeneration, ownerEpoch)
    workerPromise = creating
    creating.catch(() => {
      if (workerPromise === creating) workerPromise = null
    })
  }
  return workerPromise
}

async function createLocalWorker(
  requestGeneration: number,
  ownerEpoch: number,
): Promise<Tesseract.Worker> {
  await ensureEmbeddedModel()
  if (requestGeneration !== generation || ownerEpoch !== workerEpoch) throw cancelledError()
  const root = new URL(`${import.meta.env.BASE_URL}assets/`, document.baseURI).href
  let rejectInitialization: (reason: Error) => void = () => undefined
  const initializationError = new Promise<never>((_, reject) => {
    rejectInitialization = reject
  })
  const created = await Promise.race([
    createWorker('eng', OEM.LSTM_ONLY, {
      workerPath: `${root}ocr.worker-7.0.0.js`,
      corePath: root,
      // The model is seeded into IndexedDB below. This same-origin dead-end keeps
      // a missing/corrupt cache from ever falling back to Tesseract's CDN.
      langPath: `${root}ocr-model-local-only`,
      workerBlobURL: false,
      cachePath: MODEL_CACHE_PATH,
      cacheMethod: 'readOnly',
      errorHandler: (error) => rejectInitialization(toError(error)),
    }),
    initializationError,
  ])
  if (requestGeneration !== generation || ownerEpoch !== workerEpoch) {
    await created.terminate()
    throw cancelledError()
  }
  await created.setParameters({
    tessedit_pageseg_mode: PSM.SPARSE_TEXT,
    tessedit_char_whitelist: 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789',
    preserve_interword_spaces: '1',
    user_defined_dpi: '300',
  })
  if (requestGeneration !== generation || ownerEpoch !== workerEpoch) {
    await created.terminate()
    throw cancelledError()
  }
  worker = created
  return created
}

async function ensureEmbeddedModel(): Promise<void> {
  if (await get(MODEL_CACHE_KEY) !== undefined) return
  const { default: encoded } = await import('./ocr-language')
  const binary = atob(encoded)
  const data = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index += 1) data[index] = binary.charCodeAt(index)
  await set(MODEL_CACHE_KEY, data)
}

async function withTimeout<T>(operation: Promise<T>, requestGeneration: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => {
      // A cancelled job can remain inside third-party initialization briefly.
      // Its old timer must never terminate a newer generation's worker.
      if (requestGeneration === generation) void resetWorker()
      reject(new Error('文字参考识别超过 20 秒。'))
    }, OCR_TIMEOUT_MS)
  })
  try {
    return await Promise.race([operation, timeout])
  } finally {
    if (timer) clearTimeout(timer)
  }
}

async function resetWorker(): Promise<void> {
  workerEpoch += 1
  const active = worker
  worker = null
  workerPromise = null
  if (active) await active.terminate().catch(() => undefined)
}

function cancelledError(): Error {
  return new DOMException(CANCELLED_MESSAGE, 'AbortError')
}

function isCancelled(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

function toError(error: unknown): Error {
  return error instanceof Error ? error : new Error(String(error))
}
