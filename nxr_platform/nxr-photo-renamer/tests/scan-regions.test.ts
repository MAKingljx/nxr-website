import assert from 'node:assert/strict'
import test from 'node:test'
import { buildScanRegions, type ScanRegion } from '../src/lib/scan-regions.ts'

test('detail windows cover a portrait image completely with overlap and top-first order', () => {
  const width = 3648
  const height = 5472
  const regions = buildScanRegions(width, height)
  const detail = regions.filter((region) => region.kind === 'detail')

  assert.equal(regions[0].kind, 'overview')
  assert.equal(regions[0].maxEdge, 1600)
  assert.equal(regions[0].treatment, 'original')
  assert.equal(regions[1].treatment, 'contrast-if-low')
  assert.equal(regions.filter((region) => region.kind === 'coarse').length, 4)
  assert.ok(detail.length > 20)
  assert.ok(detail.every((region) => region.sw === region.sh && region.sw === 912))
  assert.deepEqual([...new Set(detail.map((region) => region.sy))].slice(0, 2), [0, 593])
  assert.ok(detail.every((region, index) => index === 0 || region.sy >= detail[index - 1].sy))
  assertCoverage(detail, width, height)

  const lastDetailIndex = regions.findLastIndex((region) => region.kind === 'detail')
  assert.ok(regions.slice(lastDetailIndex + 1).every((region) => region.kind === 'fallback'))
  assert.deepEqual(regions.slice(-3).map((region) => region.rotation), [90, 180, 270])
})

test('coarse windows keep large codes intact across a wide image before detail scanning', () => {
  const width = 3000
  const height = 1500
  const regions = buildScanRegions(width, height)
  const coarse = regions.filter((region) => region.kind === 'coarse')
  const detailStart = regions.findIndex((region) => region.kind === 'detail')

  assert.equal(coarse.length, 4)
  assert.ok(regions.slice(2, detailStart).every((region) => region.kind === 'coarse'))
  const codeSize = width / 5
  const codeY = height / 4 - codeSize / 2
  for (const centerX of [width / 4, width * 3 / 4]) {
    assert.ok(containsBox(coarse, {
      x: centerX - codeSize / 2,
      y: codeY,
      width: codeSize,
      height: codeSize,
    }))
  }
})

test('small images never create detail regions outside their pixel bounds', () => {
  const regions = buildScanRegions(500, 320)
  const detail = regions.filter((region) => region.kind === 'detail')
  assert.ok(detail.length >= 1)
  for (const region of detail) {
    assert.ok(region.sx >= 0 && region.sy >= 0)
    assert.ok(region.sx + region.sw <= 500)
    assert.ok(region.sy + region.sh <= 320)
  }
  assertCoverage(detail, 500, 320)
})

test('deep scan uses distinct scale, denser complete coverage, and deferred enhancements', () => {
  const width = 3648
  const height = 5472
  const standard = buildScanRegions(width, height)
  const deep = buildScanRegions(width, height, 'deep')
  const standardDetail = standard.filter(region => region.kind === 'detail')
  const deepFineOriginal = deep.filter(region => region.kind === 'fine' && region.treatment === 'original')

  assert.equal(deep[0].maxEdge, 2400)
  assert.deepEqual(deep.slice(0, 3).map(region => region.treatment), [
    'original',
    'contrast',
    'local-threshold',
  ])
  assert.ok(deepFineOriginal.length > standardDetail.length)
  assert.ok(deepFineOriginal.every(region => region.sw === 730 && region.sh === 730))
  assertCoverage(deepFineOriginal, width, height)

  const firstEnhancedFine = deep.findIndex(region => region.kind === 'fine' && region.treatment !== 'original')
  const lastOriginalFine = deep.findLastIndex(region => region.kind === 'fine' && region.treatment === 'original')
  assert.ok(firstEnhancedFine > lastOriginalFine)
  assert.deepEqual(deep.slice(-3).map(region => region.rotation), [90, 180, 270])
  assert.ok(deep.slice(-3).every(region => region.treatment === 'contrast'))
})

test('deep scan keeps every generated crop inside a very small image', () => {
  const regions = buildScanRegions(37, 29, 'deep')
  for (const region of regions) {
    assert.ok(region.sx >= 0 && region.sy >= 0)
    assert.ok(region.sw > 0 && region.sh > 0)
    assert.ok(region.sx + region.sw <= 37)
    assert.ok(region.sy + region.sh <= 29)
  }
})

function assertCoverage(regions: ScanRegion[], width: number, height: number) {
  const sampleStep = Math.max(1, Math.floor(Math.min(width, height) / 25))
  for (let y = 0; y < height; y += sampleStep) {
    for (let x = 0; x < width; x += sampleStep) {
      assert.ok(
        regions.some((region) => x >= region.sx && x < region.sx + region.sw && y >= region.sy && y < region.sy + region.sh),
        `pixel ${x},${y} is uncovered`,
      )
    }
  }
  assert.ok(regions.some((region) => region.sx + region.sw === width && region.sy + region.sh === height))
}

function containsBox(
  regions: ScanRegion[],
  box: { x: number; y: number; width: number; height: number },
): boolean {
  return regions.some((region) => (
    box.x >= region.sx
    && box.y >= region.sy
    && box.x + box.width <= region.sx + region.sw
    && box.y + box.height <= region.sy + region.sh
  ))
}
