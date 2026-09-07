import assert from 'node:assert/strict'
import test from 'node:test'
import { createManualPair } from '../src/lib/manual-pairing.ts'
import { validatePairs } from '../src/lib/pairing.ts'
import type { Pair, Photo } from '../src/lib/types.ts'

test('creates a manual pair from the immediately previous photo in natural order', () => {
  const photos = [
    photo('ten', 'IMG_10.jpg', 'error'),
    photo('two', 'IMG_2.jpg', 'none'),
    photo('one', 'IMG_1.jpg', 'none'),
  ]

  const pair = createManualPair(photos, [], 'ten', '  Cert10  ', photos.map(item => item.name))

  assert.deepEqual(pair, {
    id: 'manual:two:ten:Cert10',
    frontId: 'two',
    backId: 'ten',
    certId: 'Cert10',
    selected: true,
    manual: true,
  })
  assert.equal(photos[0].scanState, 'error')
  assert.deepEqual(photos[0].certIds, [])
})

test('rejects a back photo without a preceding photo', () => {
  const photos = [photo('first', 'IMG_1.jpg', 'none')]
  assert.throws(
    () => createManualPair(photos, [], 'first', 'CERT1', photos.map(item => item.name)),
    /没有前一张图片/,
  )
})

test('rejects photos reserved by an existing pair even when it is deselected', () => {
  const photos = [
    photo('one', 'IMG_1.jpg', 'none'),
    photo('two', 'IMG_2.jpg', 'none'),
    photo('three', 'IMG_3.jpg', 'found', ['OLD3']),
  ]
  const deselected: Pair = {
    id: 'old',
    frontId: 'one',
    backId: 'two',
    certId: 'OLD2',
    selected: false,
    manual: true,
  }

  assert.throws(
    () => createManualPair(photos, [deselected], 'three', 'NEW3', photos.map(item => item.name)),
    /已有配对使用/,
  )
})

test('rejects invalid certificate IDs after trimming', () => {
  const photos = [photo('front', 'IMG_1.jpg', 'none'), photo('back', 'IMG_2.jpg', 'none')]
  for (const certId of ['', 'ABC-123', '证书号', 'A'.repeat(65)]) {
    assert.throws(
      () => createManualPair(photos, [], 'back', certId, photos.map(item => item.name)),
      /ASCII 字母或数字/,
    )
  }
})

test('keeps duplicate certificate and existing target checks for manual pairs', () => {
  const photos = [
    photo('a', 'IMG_1.jpg', 'none'),
    photo('b', 'IMG_2.jpg', 'none'),
    photo('c', 'IMG_3.jpg', 'none'),
    photo('d', 'IMG_4.jpg', 'none'),
  ]
  const existing: Pair = {
    id: 'existing',
    frontId: 'a',
    backId: 'b',
    certId: 'CERT1',
    selected: false,
    manual: true,
  }

  assert.throws(
    () => createManualPair(photos, [existing], 'd', 'cert1', photos.map(item => item.name)),
    /证书号被多个配对重复使用/,
  )
  assert.throws(
    () => createManualPair(photos, [], 'd', 'CERT2', [...photos.map(item => item.name), 'cert2_b.WEBP']),
    /目标文件名已存在：CERT2_B\.webp/,
  )
})

test('manual validation allows an unscanned back but rejects a known successful QR mismatch', () => {
  for (const scanState of ['none', 'error'] as const) {
    const unscanned = [photo('front', 'IMG_1.jpg', 'none'), photo('back', 'IMG_2.jpg', scanState)]
    const manual = createManualPair(unscanned, [], 'back', 'MANUAL1', unscanned.map(item => item.name))
    assert.deepEqual(validatePairs(unscanned, [manual], unscanned.map(item => item.name)), new Map())
  }
  const ambiguous = [photo('front', 'IMG_1.jpg', 'none'), photo('back', 'IMG_2.jpg', 'ambiguous', ['QR1', 'QR2'])]
  assert.equal(createManualPair(ambiguous, [], 'back', 'QR2', ambiguous.map(item => item.name)).certId, 'QR2')

  const scanned = [photo('front', 'IMG_1.jpg', 'none'), photo('back', 'IMG_2.jpg', 'found', ['QR123'])]
  const mismatched: Pair = {
    id: 'manual-mismatch',
    frontId: 'front',
    backId: 'back',
    certId: 'OTHER123',
    selected: true,
    manual: true,
  }
  const errors = validatePairs(scanned, [mismatched], scanned.map(item => item.name))
  assert.match(errors.get(mismatched.id)?.join(' ') ?? '', /二维码不一致/)
  assert.throws(
    () => createManualPair(scanned, [], 'back', 'OTHER123', scanned.map(item => item.name)),
    /二维码不一致/,
  )

  const partial = [photo('front', 'IMG_1.jpg', 'none'), photo('back', 'IMG_2.jpg', 'error', ['KNOWN1'])]
  assert.throws(
    () => createManualPair(partial, [], 'back', 'OTHER1', partial.map(item => item.name)),
    /二维码不一致/,
  )
})

test('automatic pairs still require a uniquely scanned matching back', () => {
  const photos = [photo('front', 'IMG_1.jpg', 'none'), photo('back', 'IMG_2.jpg', 'none')]
  const automatic: Pair = {
    id: 'automatic',
    frontId: 'front',
    backId: 'back',
    certId: 'CERT1',
    selected: true,
  }

  const errors = validatePairs(photos, [automatic], photos.map(item => item.name))
  assert.match(errors.get(automatic.id)?.join(' ') ?? '', /二维码不一致/)
})

function photo(id: string, name: string, scanState: Photo['scanState'], certIds: string[] = []): Photo {
  return {
    id,
    name,
    file: new File(['image'], name, { type: 'image/jpeg' }),
    thumbnailUrl: '',
    scanState,
    certIds,
    qrTexts: [],
  }
}
