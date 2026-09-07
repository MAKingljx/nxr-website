import type { PixelTreatment } from './scan-regions'

const LOW_CONTRAST_SPAN = 120
const MIN_USABLE_SPAN = 8
const MAX_CONTRAST_GAIN = 6
const LOCAL_BLOCK_SIZE = 24

/**
 * Builds a fresh RGBA buffer for an enhanced jsQR pass. A null result means
 * the source does not contain enough useful contrast, or the conditional pass
 * is unnecessary. The source buffer is never mutated.
 */
export function applyPixelTreatment(
  source: Uint8ClampedArray,
  width: number,
  height: number,
  treatment: Exclude<PixelTreatment, 'original'>,
): Uint8ClampedArray | null {
  assertRgbaDimensions(source, width, height)
  if (treatment === 'local-threshold') return localThreshold(source, width, height)
  return stretchContrast(source, treatment === 'contrast-if-low')
}

function stretchContrast(source: Uint8ClampedArray, conditional: boolean): Uint8ClampedArray | null {
  const histogram = new Uint32Array(256)
  const pixels = source.length / 4
  for (let offset = 0; offset < source.length; offset += 4) {
    histogram[luminance(source[offset], source[offset + 1], source[offset + 2])] += 1
  }

  const low = percentile(histogram, pixels, 0.02)
  const high = percentile(histogram, pixels, 0.98)
  const span = high - low
  if (span < MIN_USABLE_SPAN || (conditional && span >= LOW_CONTRAST_SPAN)) return null

  const gain = Math.min(MAX_CONTRAST_GAIN, 255 / span)
  // Keep the selected percentile range centred if the gain cap prevents a full
  // 0..255 stretch. This avoids turning mild sensor noise into hard clipping.
  const mappedSpan = span * gain
  const mappedLow = (255 - mappedSpan) / 2
  const result = new Uint8ClampedArray(source.length)
  for (let offset = 0; offset < source.length; offset += 4) {
    const value = clampByte(mappedLow + (luminance(
      source[offset],
      source[offset + 1],
      source[offset + 2],
    ) - low) * gain)
    result[offset] = value
    result[offset + 1] = value
    result[offset + 2] = value
    result[offset + 3] = 255
  }
  return result
}

function localThreshold(
  source: Uint8ClampedArray,
  width: number,
  height: number,
): Uint8ClampedArray | null {
  const columns = Math.ceil(width / LOCAL_BLOCK_SIZE)
  const rows = Math.ceil(height / LOCAL_BLOCK_SIZE)
  const blockCount = columns * rows
  const sums = new Float64Array(blockCount)
  const counts = new Uint32Array(blockCount)
  const minimums = new Uint8Array(blockCount)
  const maximums = new Uint8Array(blockCount)
  minimums.fill(255)

  for (let y = 0; y < height; y += 1) {
    const blockRow = Math.floor(y / LOCAL_BLOCK_SIZE)
    for (let x = 0; x < width; x += 1) {
      const block = blockRow * columns + Math.floor(x / LOCAL_BLOCK_SIZE)
      const offset = (y * width + x) * 4
      const value = luminance(source[offset], source[offset + 1], source[offset + 2])
      sums[block] += value
      counts[block] += 1
      minimums[block] = Math.min(minimums[block], value)
      maximums[block] = Math.max(maximums[block], value)
    }
  }

  const thresholds = new Float32Array(blockCount)
  const active = new Uint8Array(blockCount)
  let activeBlocks = 0
  for (let blockY = 0; blockY < rows; blockY += 1) {
    for (let blockX = 0; blockX < columns; blockX += 1) {
      let sum = 0
      let count = 0
      let minimum = 255
      let maximum = 0
      for (let neighborY = Math.max(0, blockY - 1); neighborY <= Math.min(rows - 1, blockY + 1); neighborY += 1) {
        for (let neighborX = Math.max(0, blockX - 1); neighborX <= Math.min(columns - 1, blockX + 1); neighborX += 1) {
          const neighbor = neighborY * columns + neighborX
          sum += sums[neighbor]
          count += counts[neighbor]
          minimum = Math.min(minimum, minimums[neighbor])
          maximum = Math.max(maximum, maximums[neighbor])
        }
      }
      const block = blockY * columns + blockX
      const span = maximum - minimum
      if (span >= MIN_USABLE_SPAN) {
        active[block] = 1
        activeBlocks += 1
        thresholds[block] = sum / count - Math.min(4, span * 0.04)
      }
    }
  }
  if (activeBlocks === 0) return null

  const result = new Uint8ClampedArray(source.length)
  for (let y = 0; y < height; y += 1) {
    const blockRow = Math.floor(y / LOCAL_BLOCK_SIZE)
    for (let x = 0; x < width; x += 1) {
      const block = blockRow * columns + Math.floor(x / LOCAL_BLOCK_SIZE)
      const offset = (y * width + x) * 4
      const value = active[block] && luminance(
        source[offset],
        source[offset + 1],
        source[offset + 2],
      ) <= thresholds[block] ? 0 : 255
      result[offset] = value
      result[offset + 1] = value
      result[offset + 2] = value
      result[offset + 3] = 255
    }
  }
  return result
}

function percentile(histogram: Uint32Array, total: number, fraction: number): number {
  const target = Math.max(1, Math.ceil(total * fraction))
  let seen = 0
  for (let value = 0; value < histogram.length; value += 1) {
    seen += histogram[value]
    if (seen >= target) return value
  }
  return 255
}

function luminance(red: number, green: number, blue: number): number {
  return (77 * red + 150 * green + 29 * blue) >>> 8
}

function clampByte(value: number): number {
  return Math.min(255, Math.max(0, Math.round(value)))
}

function assertRgbaDimensions(source: Uint8ClampedArray, width: number, height: number) {
  if (!Number.isInteger(width) || !Number.isInteger(height) || width <= 0 || height <= 0
    || source.length !== width * height * 4) {
    throw new Error('Invalid RGBA image dimensions.')
  }
}
