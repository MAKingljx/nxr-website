import jsQR, { type QRCode } from 'jsqr'
import { parseCertificateLink } from './lib/pairing'
import {
  buildScanRegions,
  countsTowardConflictVerification,
  type ScanRegion,
} from './lib/scan-regions'
import { applyPixelTreatment } from './lib/qr-image-processing'
import { SCAN_LIMITS, type ScanMode } from './lib/scan-policy'

interface ScanRequest {
  id: number
  file: File
  mode?: ScanMode
}

interface ScanResponse {
  id: number
  certIds: string[]
  qrTexts: string[]
  error?: string
}

const MAX_FILE_BYTES = 40 * 1024 * 1024
const MAX_IMAGE_PIXELS = 120_000_000
const MAX_CODES = 6
const VERIFY_REGIONS_AFTER_FIRST_CERT = 8
const DEEP_VERIFY_REGIONS_AFTER_FIRST_CERT = 16
const workerScope = globalThis as unknown as {
  onmessage: ((event: MessageEvent<ScanRequest>) => void) | null
  postMessage: (response: ScanResponse) => void
}

workerScope.onmessage = async (event) => {
  const { id, file } = event.data
  const mode: ScanMode = event.data.mode === 'deep' ? 'deep' : 'standard'
  try {
    workerScope.postMessage({ id, ...(await scan(file, mode)) })
  } catch (cause) {
    workerScope.postMessage({
      id,
      certIds: [],
      qrTexts: [],
      error: chineseMessage(cause, '无法在本地解码这张图片。'),
    })
  }
}

async function scan(file: File, mode: ScanMode): Promise<Omit<ScanResponse, 'id'>> {
  if (!(file instanceof Blob) || file.size <= 0) {
    return { certIds: [], qrTexts: [], error: '图片文件为空。' }
  }
  if (file.size > MAX_FILE_BYTES) {
    return { certIds: [], qrTexts: [], error: '图片超过 40 MB 的本地识别上限。' }
  }

  const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' })
  try {
    if (bitmap.width <= 0 || bitmap.height <= 0) {
      return { certIds: [], qrTexts: [], error: '图片尺寸无效。' }
    }
    if (bitmap.width * bitmap.height > MAX_IMAGE_PIXELS) {
      return { certIds: [], qrTexts: [], error: '图片超过 1.2 亿像素的本地识别上限。' }
    }

    const startedAt = performance.now()
    const deadline = startedAt + SCAN_LIMITS[mode].workerBudgetMs
    const texts = new Set<string>()
    const regions = buildScanRegions(bitmap.width, bitmap.height, mode)
    let timedOut = false
    let verifyRegionsRemaining: number | null = null
    for (const region of regions) {
      if (texts.size >= MAX_CODES) break
      if (performance.now() >= deadline) {
        timedOut = true
        break
      }
      const outcome = scanRegion(bitmap, region, texts, deadline)
      const currentCertIds = certificateIds(texts)
      if (currentCertIds.length > 1) break
      if (outcome === 'timed-out') {
        timedOut = true
        break
      }
      if (currentCertIds.length === 1 && outcome === 'scanned') {
        verifyRegionsRemaining = verifyRegionsRemaining ?? (mode === 'deep'
          ? DEEP_VERIFY_REGIONS_AFTER_FIRST_CERT
          : VERIFY_REGIONS_AFTER_FIRST_CERT)
        // Low-resolution candidates help find soft codes, but do not replace
        // the existing full-resolution conflict checks after the first match.
        if (countsTowardConflictVerification(region)) {
          verifyRegionsRemaining -= 1
          if (verifyRegionsRemaining <= 0) break
        }
      }
    }

    const qrTexts = [...texts]
    const certIds = certificateIds(texts)
    if (timedOut) {
      return {
        certIds,
        qrTexts,
        error: '二维码识别达到时间上限，仍有部分区域未检查，请缩小图片或重试。',
      }
    }
    return { certIds, qrTexts }
  } finally {
    bitmap.close()
  }
}

function scanRegion(
  bitmap: ImageBitmap,
  region: ScanRegion,
  texts: Set<string>,
  deadline: number,
): 'scanned' | 'skipped' | 'timed-out' {
  const scale = Math.min(1, region.maxEdge / Math.max(region.sw, region.sh))
  const sourceWidth = Math.max(1, Math.round(region.sw * scale))
  const sourceHeight = Math.max(1, Math.round(region.sh * scale))
  const rotated = region.rotation === 90 || region.rotation === 270
  const canvas = new OffscreenCanvas(rotated ? sourceHeight : sourceWidth, rotated ? sourceWidth : sourceHeight)
  const context = canvas.getContext('2d', { willReadFrequently: true })
  if (!context) throw new Error('当前浏览器不支持离屏画布，无法识别二维码。')

  context.save()
  applyRotation(context, region.rotation, canvas.width, canvas.height)
  context.drawImage(
    bitmap,
    region.sx,
    region.sy,
    region.sw,
    region.sh,
    0,
    0,
    sourceWidth,
    sourceHeight,
  )
  context.restore()

  const source = context.getImageData(0, 0, canvas.width, canvas.height)
  let pixels: Uint8ClampedArray = source.data
  if (region.treatment !== 'original') {
    const enhanced = applyPixelTreatment(source.data, source.width, source.height, region.treatment)
    if (!enhanced) return 'skipped'
    pixels = enhanced
  }
  if (performance.now() >= deadline) return 'timed-out'

  for (let index = 0; index < MAX_CODES; index += 1) {
    if (index > 0 && performance.now() >= deadline) return 'timed-out'
    const code = jsQR(pixels, source.width, source.height, { inversionAttempts: 'attemptBoth' })
    if (!code) break
    if (code.data.trim()) texts.add(code.data.trim())
    maskCode(pixels, source.width, source.height, code)
  }
  return 'scanned'
}

function applyRotation(
  context: OffscreenCanvasRenderingContext2D,
  rotation: ScanRegion['rotation'],
  width: number,
  height: number,
) {
  if (rotation === 90) {
    context.translate(width, 0)
    context.rotate(Math.PI / 2)
  } else if (rotation === 180) {
    context.translate(width, height)
    context.rotate(Math.PI)
  } else if (rotation === 270) {
    context.translate(0, height)
    context.rotate(-Math.PI / 2)
  }
}

function maskCode(data: Uint8ClampedArray, width: number, height: number, code: QRCode) {
  const points = [
    code.location.topLeftCorner,
    code.location.topRightCorner,
    code.location.bottomLeftCorner,
    code.location.bottomRightCorner,
  ]
  const rawMinX = Math.min(...points.map((point) => point.x))
  const rawMaxX = Math.max(...points.map((point) => point.x))
  const rawMinY = Math.min(...points.map((point) => point.y))
  const rawMaxY = Math.max(...points.map((point) => point.y))
  const padding = Math.max(8, Math.ceil(Math.max(rawMaxX - rawMinX, rawMaxY - rawMinY) * 0.15))
  const minX = Math.max(0, Math.floor(rawMinX - padding))
  const maxX = Math.min(width - 1, Math.ceil(rawMaxX + padding))
  const minY = Math.max(0, Math.floor(rawMinY - padding))
  const maxY = Math.min(height - 1, Math.ceil(rawMaxY + padding))

  for (let y = minY; y <= maxY; y += 1) {
    for (let x = minX; x <= maxX; x += 1) {
      const offset = (y * width + x) * 4
      data[offset] = 255
      data[offset + 1] = 255
      data[offset + 2] = 255
      data[offset + 3] = 255
    }
  }
}

function chineseMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error && /[\u3400-\u9fff]/.test(cause.message) ? cause.message : fallback
}

function certificateIds(texts: Set<string>): string[] {
  return [...new Set([...texts].map(parseCertificateLink).filter((id): id is string => id !== null))]
}
