import createWebpDecoderModule from '@jsquash/webp/codec/dec/webp_dec.js'
import webpDecoderWasmUrl from '@jsquash/webp/codec/dec/webp_dec.wasm?url'
import createWebpEncoderModule, {
  type EncodeOptions,
} from '@jsquash/webp/codec/enc/webp_enc.js'
import webpEncoderWasmUrl from '@jsquash/webp/codec/enc/webp_enc.wasm?url'

interface ConversionRequest {
  id: number
  file: File
  mode: 'quality' | 'lossless'
  quality?: number
}

interface ConversionSuccess {
  id: number
  ok: true
  buffer: ArrayBuffer
}

interface ConversionFailure {
  id: number
  ok: false
  error: string
}

type ConversionResponse = ConversionSuccess | ConversionFailure

const MAX_FILE_BYTES = 24 * 1024 * 1024
const MAX_OUTPUT_BYTES = 24 * 1024 * 1024
const MAX_IMAGE_PIXELS = 40_000_000
const MAX_WEBP_DIMENSION = 16_383

const LOSSLESS_OPTIONS: EncodeOptions = {
  quality: 100,
  target_size: 0,
  target_PSNR: 0,
  method: 4,
  sns_strength: 50,
  filter_strength: 60,
  filter_sharpness: 0,
  filter_type: 1,
  partitions: 0,
  segments: 4,
  pass: 1,
  show_compressed: 0,
  preprocessing: 0,
  autofilter: 0,
  partition_limit: 0,
  alpha_compression: 1,
  alpha_filtering: 1,
  alpha_quality: 100,
  lossless: 1,
  exact: 1,
  image_hint: 0,
  emulate_jpeg_size: 0,
  thread_level: 0,
  low_memory: 1,
  near_lossless: 100,
  use_delta_palette: 0,
  use_sharp_yuv: 0,
}

const workerScope = globalThis as unknown as {
  onmessage: ((event: MessageEvent<ConversionRequest>) => void) | null
  postMessage: (response: ConversionResponse, transfer?: Transferable[]) => void
}

let encoderModulePromise: ReturnType<typeof initializeEncoder> | null = null
let decoderModulePromise: ReturnType<typeof initializeDecoder> | null = null

workerScope.onmessage = async (event) => {
  const { id, file, mode, quality } = event.data
  try {
    const buffer = await convert(file, mode, quality)
    workerScope.postMessage({ id, ok: true, buffer }, [buffer])
  } catch (cause) {
    workerScope.postMessage({
      id,
      ok: false,
      error: chineseMessage(cause, '无法在本机生成 WebP。'),
    })
  }
}

async function convert(
  file: File,
  mode: 'quality' | 'lossless',
  requestedQuality?: number,
): Promise<ArrayBuffer> {
  if (!(file instanceof Blob) || file.size <= 0) {
    throw new Error('图片文件为空。')
  }
  if (file.size > MAX_FILE_BYTES) {
    throw new Error('原图超过 24 MB 的本地转换上限。')
  }
  if (mode !== 'quality' && mode !== 'lossless') throw new Error('WebP 转换模式无效。')
  const quality = mode === 'quality' ? requireQuality(requestedQuality) : 1

  const sourceBuffer = await file.arrayBuffer()
  const sourceBytes = new Uint8Array(sourceBuffer)
  rejectAnimatedImage(sourceBytes)

  const sourceWebp = inspectWebp(sourceBytes)
  if (sourceWebp.isWebp) {
    if (!sourceWebp.valid || sourceWebp.width === null || sourceWebp.height === null) {
      throw new Error('现有 WebP 文件结构无效，无法安全保留。')
    }
    validateDimensions(sourceWebp.width, sourceWebp.height)
    if (sourceBuffer.byteLength > MAX_OUTPUT_BYTES) {
      throw new Error('现有 WebP 超过 24 MB 的系统导入上限。')
    }
    await verifyNativeDecode(sourceBuffer, sourceWebp.width, sourceWebp.height, '现有 WebP 文件已损坏，无法安全保留。')
    return sourceBuffer
  }

  const bitmap = await createImageBitmap(file, {
    imageOrientation: 'from-image',
    colorSpaceConversion: 'default',
  })
  let canvas: OffscreenCanvas | null = null
  try {
    validateDimensions(bitmap.width, bitmap.height)
    canvas = new OffscreenCanvas(bitmap.width, bitmap.height)
    const context = canvas.getContext('2d', {
      alpha: true,
      colorSpace: 'srgb',
      willReadFrequently: mode === 'lossless',
    })
    if (!context) throw new Error('当前环境不支持离屏画布，无法转换。')

    context.drawImage(bitmap, 0, 0)
    if (mode === 'quality') {
      const output = await encodeQuality(canvas, context, bitmap.width, bitmap.height, quality)
      await verifyEncodedWebp(output, bitmap.width, bitmap.height)
      return output
    }

    // The compatibility path keeps the browser's displayed RGBA bytes exact.
    const sourcePixels = context.getImageData(0, 0, bitmap.width, bitmap.height)
    bitmap.close()
    canvas.width = 1
    canvas.height = 1
    canvas = null

    let encodingPixels: ImageData | null = compensateForCanvasRendering(sourcePixels)
    const encoder = await encoderCodec()
    const encoded = encoder.encode(
      encodingPixels.data,
      encodingPixels.width,
      encodingPixels.height,
      LOSSLESS_OPTIONS,
    )
    if (!encoded) throw new Error('libwebp 无法编码这张图片。')

    const output = exactArrayBuffer(encoded)
    if (output.byteLength > MAX_OUTPUT_BYTES) {
      throw new Error('无损 WebP 超过 24 MB 的系统导入上限，原图已保留。')
    }
    const outputInfo = inspectWebp(new Uint8Array(output))
    if (
      !outputInfo.isWebp ||
      !outputInfo.valid ||
      outputInfo.animated ||
      !outputInfo.lossless ||
      outputInfo.width !== sourcePixels.width ||
      outputInfo.height !== sourcePixels.height
    ) {
      throw new Error('无损转换结果不是有效的静态 WebP。')
    }

    const decoder = await decoderCodec()
    let rawPixels: ImageData | null = decoder.decode(output)
    if (
      !rawPixels ||
      rawPixels.width !== encodingPixels.width ||
      rawPixels.height !== encodingPixels.height ||
      !equalPixels(encodingPixels.data, rawPixels.data)
    ) {
      throw new Error('libwebp 无损像素回读校验失败，原图已保留。')
    }
    rawPixels = null
    encodingPixels = null

    const verifiedPixels = await renderWebpPixels(output)
    if (!equalPixels(sourcePixels.data, verifiedPixels.data)) {
      throw new Error('无损 WebP 像素校验失败，原图已保留。')
    }
    return output
  } finally {
    bitmap.close()
    if (canvas) {
      canvas.width = 1
      canvas.height = 1
    }
  }
}

function requireQuality(value: number | undefined): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0 || value > 1) {
    throw new Error('WebP 质量必须大于 0 且不超过 1。')
  }
  return value
}

async function encodeQuality(
  canvas: OffscreenCanvas,
  context: OffscreenCanvasRenderingContext2D,
  width: number,
  height: number,
  quality: number,
): Promise<ArrayBuffer> {
  if (typeof canvas.convertToBlob === 'function') {
    try {
      const blob = await canvas.convertToBlob({ type: 'image/webp', quality })
      if (blob.size > 0 && blob.type.toLowerCase() === 'image/webp') {
        return blob.arrayBuffer()
      }
      // Some implementations return another format instead of reporting that
      // WebP encoding is unsupported. Treat that result as unsupported.
    } catch (cause) {
      if (!isUnsupportedNativeWebpError(cause)) throw cause
    }
  }

  const pixels = context.getImageData(0, 0, width, height)
  const encoder = await encoderCodec()
  const encoded = encoder.encode(
    pixels.data,
    width,
    height,
    { ...LOSSLESS_OPTIONS, quality: quality * 100, lossless: 0, exact: 0 },
  )
  if (!encoded) throw new Error('libwebp 无法编码这张图片。')
  return exactArrayBuffer(encoded)
}

function isUnsupportedNativeWebpError(cause: unknown): boolean {
  return cause instanceof DOMException && cause.name === 'NotSupportedError'
}

async function verifyEncodedWebp(output: ArrayBuffer, width: number, height: number): Promise<void> {
  if (output.byteLength <= 0 || output.byteLength > MAX_OUTPUT_BYTES) {
    throw new Error('WebP 输出超过 24 MB 的系统导入上限，原图已保留。')
  }
  const info = inspectWebp(new Uint8Array(output))
  if (!info.isWebp || !info.valid || info.animated || info.width !== width || info.height !== height) {
    throw new Error('转换结果不是有效的原尺寸静态 WebP。')
  }
  await verifyNativeDecode(output, width, height, 'WebP 输出无法回读，原图已保留。')
}

async function verifyNativeDecode(
  buffer: ArrayBuffer,
  width: number,
  height: number,
  invalidMessage: string,
): Promise<void> {
  let decoded: ImageBitmap | null = null
  try {
    decoded = await createImageBitmap(new Blob([buffer], { type: 'image/webp' }))
    if (decoded.width !== width || decoded.height !== height) throw new Error(invalidMessage)
  } catch {
    throw new Error(invalidMessage)
  } finally {
    decoded?.close()
  }
}

async function encoderCodec() {
  encoderModulePromise ??= initializeEncoder()
  return encoderModulePromise
}

async function decoderCodec() {
  decoderModulePromise ??= initializeDecoder()
  return decoderModulePromise
}

async function initializeEncoder() {
  const module = await compileWasm(webpEncoderWasmUrl)
  return createWebpEncoderModule(moduleOptions(module))
}

async function initializeDecoder() {
  const module = await compileWasm(webpDecoderWasmUrl)
  return createWebpDecoderModule(moduleOptions(module))
}

function moduleOptions(
  module: WebAssembly.Module,
): Parameters<typeof createWebpEncoderModule>[0] {
  const options = {
    noInitialRun: true,
    instantiateWasm(
      imports: WebAssembly.Imports,
      success: (instance: WebAssembly.Instance) => void,
    ) {
      const instance = new WebAssembly.Instance(module, imports)
      success(instance)
      return instance.exports
    },
  }
  // @jsquash's declaration types the Emscripten callback as a Module, while
  // its generated glue actually calls it with the instantiated Instance.
  return options as unknown as Parameters<typeof createWebpEncoderModule>[0]
}

async function compileWasm(url: string): Promise<WebAssembly.Module> {
  if (url.startsWith('data:')) {
    const comma = url.indexOf(',')
    if (comma < 0) throw new Error('内置 WebP 编码器资源无效。')
    const base64 = url.slice(comma + 1)
    const binary = atob(base64)
    const bytes = new Uint8Array(binary.length)
    for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index)
    return WebAssembly.compile(bytes)
  }

  // Vite serves WASM as a same-origin URL in local development. Production
  // inlines it into the worker bundle so its connect-src policy stays closed.
  const response = await fetch(url)
  if (!response.ok) throw new Error('无法加载本地 WebP 编码器。')
  return WebAssembly.compile(await response.arrayBuffer())
}

function validateDimensions(width: number, height: number): void {
  if (!Number.isInteger(width) || !Number.isInteger(height) || width <= 0 || height <= 0) {
    throw new Error('图片尺寸无效。')
  }
  if (width > MAX_WEBP_DIMENSION || height > MAX_WEBP_DIMENSION) {
    throw new Error('图片单边超过 WebP 的 16383 像素上限，无法在不缩放时转换。')
  }
  if (width * height > MAX_IMAGE_PIXELS) {
    throw new Error('图片超过 4000 万像素的本地无损转换内存上限，未做缩放。')
  }
}

function exactArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  return bytes.byteOffset === 0 && bytes.byteLength === bytes.buffer.byteLength
    ? (bytes.buffer as ArrayBuffer)
    : bytes.slice().buffer
}

function equalPixels(left: Uint8ClampedArray, right: Uint8ClampedArray): boolean {
  if (left.length !== right.length) return false
  for (let index = 0; index < left.length; index += 1) {
    if (left[index] !== right[index]) return false
  }
  return true
}

/**
 * Canvas stores translucent colors premultiplied and exposes straight RGBA.
 * libwebp correctly preserves the straight bytes, but Chromium premultiplies
 * WebP with truncation when rendering it. Choose the equivalent straight byte
 * that recreates the canvas's existing premultiplied value on the next draw.
 */
function compensateForCanvasRendering(source: ImageData): ImageData {
  const data = new Uint8ClampedArray(source.data)
  for (let offset = 0; offset < data.length; offset += 4) {
    const alpha = data[offset + 3]
    if (alpha === 0) {
      data[offset] = 0
      data[offset + 1] = 0
      data[offset + 2] = 0
      continue
    }
    if (alpha === 255) continue
    for (let channel = 0; channel < 3; channel += 1) {
      const premultiplied = Math.round((data[offset + channel] * alpha) / 255)
      data[offset + channel] = Math.min(255, Math.ceil((premultiplied * 255) / alpha))
    }
  }
  return new ImageData(data, source.width, source.height)
}

async function renderWebpPixels(buffer: ArrayBuffer): Promise<ImageData> {
  const bitmap = await createImageBitmap(new Blob([buffer], { type: 'image/webp' }))
  let canvas: OffscreenCanvas | null = null
  try {
    canvas = new OffscreenCanvas(bitmap.width, bitmap.height)
    const context = canvas.getContext('2d', {
      alpha: true,
      colorSpace: 'srgb',
      willReadFrequently: true,
    })
    if (!context) throw new Error('当前浏览器无法回读无损 WebP。')
    context.drawImage(bitmap, 0, 0)
    return context.getImageData(0, 0, bitmap.width, bitmap.height)
  } finally {
    bitmap.close()
    if (canvas) {
      canvas.width = 1
      canvas.height = 1
    }
  }
}

function rejectAnimatedImage(bytes: Uint8Array): void {
  const webp = inspectWebp(bytes)
  if (webp.animated) throw new Error('不支持动态 WebP；为避免只保留第一帧，未处理此文件。')
  if (isAnimatedPng(bytes)) throw new Error('不支持 APNG 动图；为避免只保留第一帧，未处理此文件。')
  if (isAnimatedGif(bytes)) throw new Error('不支持 GIF 动图；为避免只保留第一帧，未处理此文件。')
}

interface WebpInfo {
  isWebp: boolean
  valid: boolean
  animated: boolean
  lossless: boolean
  width: number | null
  height: number | null
}

function inspectWebp(bytes: Uint8Array): WebpInfo {
  const isWebp =
    bytes.length >= 12 &&
    ascii(bytes, 0, 4) === 'RIFF' &&
    ascii(bytes, 8, 4) === 'WEBP'
  if (!isWebp) {
    return {
      isWebp: false,
      valid: false,
      animated: false,
      lossless: false,
      width: null,
      height: null,
    }
  }

  const riffBytes = readUint32LE(bytes, 4) + 8
  if (riffBytes !== bytes.length) {
    return {
      isWebp: true,
      valid: false,
      animated: false,
      lossless: false,
      width: null,
      height: null,
    }
  }

  let offset = 12
  let animated = false
  let lossless = false
  let width: number | null = null
  let height: number | null = null
  let imageChunks = 0
  while (offset + 8 <= bytes.length) {
    const type = ascii(bytes, offset, 4)
    const size = readUint32LE(bytes, offset + 4)
    const dataOffset = offset + 8
    const next = offset + 8 + size + (size & 1)
    if (!Number.isSafeInteger(next) || next <= offset || next > bytes.length) {
      return { isWebp: true, valid: false, animated, lossless, width, height }
    }

    if (type === 'ANIM' || type === 'ANMF') animated = true
    if (type === 'VP8X') {
      if (size !== 10) {
        return { isWebp: true, valid: false, animated, lossless, width, height }
      }
      animated ||= (bytes[dataOffset] & 0x02) !== 0
      width = readUint24LE(bytes, dataOffset + 4) + 1
      height = readUint24LE(bytes, dataOffset + 7) + 1
    } else if (type === 'VP8L') {
      imageChunks += 1
      lossless = true
      if (size < 5 || bytes[dataOffset] !== 0x2f) {
        return { isWebp: true, valid: false, animated, lossless, width, height }
      }
      const bits = readUint32LE(bytes, dataOffset + 1)
      const chunkWidth = (bits & 0x3fff) + 1
      const chunkHeight = ((bits >>> 14) & 0x3fff) + 1
      width ??= chunkWidth
      height ??= chunkHeight
      if (width !== chunkWidth || height !== chunkHeight) {
        return { isWebp: true, valid: false, animated, lossless, width, height }
      }
    } else if (type === 'VP8 ') {
      imageChunks += 1
      if (
        size < 10 ||
        bytes[dataOffset + 3] !== 0x9d ||
        bytes[dataOffset + 4] !== 0x01 ||
        bytes[dataOffset + 5] !== 0x2a
      ) {
        return { isWebp: true, valid: false, animated, lossless, width, height }
      }
      const chunkWidth = readUint16LE(bytes, dataOffset + 6) & 0x3fff
      const chunkHeight = readUint16LE(bytes, dataOffset + 8) & 0x3fff
      width ??= chunkWidth
      height ??= chunkHeight
      if (width !== chunkWidth || height !== chunkHeight) {
        return { isWebp: true, valid: false, animated, lossless, width, height }
      }
    }
    offset = next
  }

  const valid = offset === bytes.length && imageChunks === 1 && width !== null && height !== null
  return { isWebp: true, valid, animated, lossless, width, height }
}

function isAnimatedPng(bytes: Uint8Array): boolean {
  const signature = [137, 80, 78, 71, 13, 10, 26, 10]
  if (bytes.length < 8 || signature.some((value, index) => bytes[index] !== value)) return false
  let offset = 8
  while (offset + 12 <= bytes.length) {
    const size = readUint32BE(bytes, offset)
    const type = ascii(bytes, offset + 4, 4)
    if (type === 'acTL') return true
    const next = offset + 12 + size
    if (!Number.isSafeInteger(next) || next <= offset || next > bytes.length) break
    offset = next
  }
  return false
}

function isAnimatedGif(bytes: Uint8Array): boolean {
  const header = ascii(bytes, 0, 6)
  if (header !== 'GIF87a' && header !== 'GIF89a') return false
  if (bytes.length < 13) return false

  let offset = 13
  const packed = bytes[10]
  if (packed & 0x80) offset += 3 * (1 << ((packed & 0x07) + 1))
  let frames = 0

  while (offset < bytes.length) {
    const marker = bytes[offset++]
    if (marker === 0x3b) break
    if (marker === 0x21) {
      if (offset >= bytes.length) break
      offset += 1
      offset = skipGifSubBlocks(bytes, offset)
      continue
    }
    if (marker !== 0x2c || offset + 9 > bytes.length) break

    frames += 1
    if (frames > 1) return true
    const imagePacked = bytes[offset + 8]
    offset += 9
    if (imagePacked & 0x80) offset += 3 * (1 << ((imagePacked & 0x07) + 1))
    if (offset >= bytes.length) break
    offset += 1
    offset = skipGifSubBlocks(bytes, offset)
  }
  return false
}

function skipGifSubBlocks(bytes: Uint8Array, start: number): number {
  let offset = start
  while (offset < bytes.length) {
    const size = bytes[offset++]
    if (size === 0) return offset
    offset += size
    if (offset > bytes.length) return bytes.length
  }
  return offset
}

function ascii(bytes: Uint8Array, offset: number, length: number): string {
  if (offset < 0 || offset + length > bytes.length) return ''
  let value = ''
  for (let index = 0; index < length; index += 1) value += String.fromCharCode(bytes[offset + index])
  return value
}

function readUint32LE(bytes: Uint8Array, offset: number): number {
  return (
    bytes[offset] |
    (bytes[offset + 1] << 8) |
    (bytes[offset + 2] << 16) |
    (bytes[offset + 3] << 24)
  ) >>> 0
}

function readUint16LE(bytes: Uint8Array, offset: number): number {
  return bytes[offset] | (bytes[offset + 1] << 8)
}

function readUint24LE(bytes: Uint8Array, offset: number): number {
  return bytes[offset] | (bytes[offset + 1] << 8) | (bytes[offset + 2] << 16)
}

function readUint32BE(bytes: Uint8Array, offset: number): number {
  return (
    (bytes[offset] << 24) |
    (bytes[offset + 1] << 16) |
    (bytes[offset + 2] << 8) |
    bytes[offset + 3]
  ) >>> 0
}

function chineseMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error && /[\u3400-\u9fff]/.test(cause.message) ? cause.message : fallback
}
