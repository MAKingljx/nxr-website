import assert from 'node:assert/strict'
import test from 'node:test'
import jsQR from 'jsqr'
import QRCode from 'qrcode'
import { applyPixelTreatment } from '../src/lib/qr-image-processing.ts'

test('conditional contrast skips normal contrast and unusably flat images', () => {
  const normal = rgbaRow([0, 255, 0, 255])
  const flat = rgbaRow([127, 127, 127, 127])

  assert.equal(applyPixelTreatment(normal, 4, 1, 'contrast-if-low'), null)
  assert.equal(applyPixelTreatment(flat, 4, 1, 'contrast-if-low'), null)
})

test('contrast treatment recovers a valid QR below jsQR dynamic range without changing source', () => {
  const text = 'https://nxrgrading.com/card/8123456789'
  const { pixels, width, height } = renderQr(text, (isDark) => isDark ? 119 : 136)
  const original = pixels.slice()

  assert.equal(jsQR(pixels, width, height, { inversionAttempts: 'attemptBoth' }), null)
  const enhanced = applyPixelTreatment(pixels, width, height, 'contrast')

  assert.ok(enhanced)
  assert.deepEqual(pixels, original)
  assert.equal(jsQR(enhanced, width, height, { inversionAttempts: 'attemptBoth' })?.data, text)
})

test('local threshold separates low-contrast modules under uneven illumination', () => {
  const text = 'https://nxrgrading.com/card/7987654321'
  const { pixels, width, height } = renderQr(text, (isDark, x, imageWidth) => {
    const illumination = 80 + Math.round(120 * x / Math.max(1, imageWidth - 1))
    return isDark ? illumination : illumination + 16
  })

  const enhanced = applyPixelTreatment(pixels, width, height, 'local-threshold')
  assert.ok(enhanced)
  assert.equal(jsQR(enhanced, width, height, { inversionAttempts: 'attemptBoth' })?.data, text)
  assert.ok(enhanced.every((value, index) => index % 4 === 3 || value === 0 || value === 255))
})

test('pixel treatment rejects malformed RGBA dimensions', () => {
  assert.throws(
    () => applyPixelTreatment(new Uint8ClampedArray(7), 2, 1, 'contrast'),
    /Invalid RGBA image dimensions/,
  )
})

function rgbaRow(values: number[]): Uint8ClampedArray {
  const pixels = new Uint8ClampedArray(values.length * 4)
  values.forEach((value, index) => {
    const offset = index * 4
    pixels[offset] = value
    pixels[offset + 1] = value
    pixels[offset + 2] = value
    pixels[offset + 3] = 255
  })
  return pixels
}

function renderQr(
  text: string,
  shade: (isDark: boolean, x: number, width: number) => number,
): { pixels: Uint8ClampedArray, width: number, height: number } {
  const qr = QRCode.create(text, { errorCorrectionLevel: 'M' })
  const quietZone = 4
  const scale = 8
  const width = (qr.modules.size + quietZone * 2) * scale
  const pixels = new Uint8ClampedArray(width * width * 4)

  for (let y = 0; y < width; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const moduleX = Math.floor(x / scale) - quietZone
      const moduleY = Math.floor(y / scale) - quietZone
      const isDark = moduleX >= 0 && moduleY >= 0
        && moduleX < qr.modules.size && moduleY < qr.modules.size
        && qr.modules.get(moduleY, moduleX) === 1
      const value = Math.min(255, Math.max(0, shade(isDark, x, width)))
      const offset = (y * width + x) * 4
      pixels[offset] = value
      pixels[offset + 1] = value
      pixels[offset + 2] = value
      pixels[offset + 3] = 255
    }
  }
  return { pixels, width, height: width }
}
