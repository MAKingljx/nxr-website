import assert from 'node:assert/strict'
import test from 'node:test'
import {
  isSupportedImage,
  naturalCompare,
  parseCertificateLink,
  suggestPairs,
  validatePairs,
} from '../src/lib/pairing.ts'
import type { Pair, Photo } from '../src/lib/types.ts'

test('naturalCompare sorts numbered camera files naturally', () => {
  const names = ['IMG_10.jpg', 'IMG_2.jpg', 'IMG_1.jpg']
  assert.deepEqual(names.sort(naturalCompare), ['IMG_1.jpg', 'IMG_2.jpg', 'IMG_10.jpg'])
  assert.equal(isSupportedImage('card.WEBP'), true)
  assert.equal(isSupportedImage('card.svg'), false)
  const tied = ['img_2.jpg', 'IMG_2.jpg', 'IMG_02.jpg']
  const first = [...tied].sort(naturalCompare)
  const second = [...tied].reverse().sort(naturalCompare)
  assert.deepEqual(first, second)
})

test('parseCertificateLink accepts only the public card route on the NXR host', () => {
  assert.equal(parseCertificateLink('https://nxrgrading.com/card/AbC123'), 'AbC123')
  assert.equal(parseCertificateLink('http://www.nxrgrading.com/card/VRA003?from=qr'), 'VRA003')
  assert.equal(parseCertificateLink('/card/5703018202'), '5703018202')
  assert.equal(parseCertificateLink('https://evil.example/card/AbC123'), null)
  assert.equal(parseCertificateLink('https://user:pass@nxrgrading.com/card/AbC123'), null)
  assert.equal(parseCertificateLink('//nxrgrading.com/card/AbC123'), null)
  assert.equal(parseCertificateLink('/card/../admin'), null)
  assert.equal(parseCertificateLink('/card/../card/AbC123'), null)
  assert.equal(parseCertificateLink('https://nxrgrading.com/x/../card/AbC123'), null)
  assert.equal(parseCertificateLink('https://nxrgrading.com/card/%2e%2e/card/AbC123'), null)
  assert.equal(parseCertificateLink('https://nxrgrading.com/card%2fAbC123'), null)
  assert.equal(parseCertificateLink('https://nxrgrading.com/cards/AbC123'), null)
  assert.equal(parseCertificateLink('https://nxrgrading.com/card/A_B'), null)
})

test('printed labels without a URL scheme still require the exact NXR host and safe path', () => {
  assert.equal(parseCertificateLink('nxrgrading.com/card/7003659840'), '7003659840')
  assert.equal(parseCertificateLink('www.nxrgrading.com/card/VRA003'), 'VRA003')
  assert.equal(parseCertificateLink('nxrgrading.com.evil.example/card/7003659840'), null)
  assert.equal(parseCertificateLink('nxrgrading.com/card/../card/7003659840'), null)
  assert.equal(parseCertificateLink('nxrgrading.com/card%2f7003659840'), null)
  assert.equal(parseCertificateLink('evil.example/card/7003659840'), null)
})

test('suggestPairs uses the immediately previous image and never reuses a photo', () => {
  const photos = [
    photo('1', 'IMG_1.jpg', 'none'),
    photo('2', 'IMG_2.jpg', 'found', ['CERT1']),
    photo('3', 'IMG_3.jpg', 'found', ['CERT2']),
    photo('4', 'IMG_4.jpg', 'none'),
    photo('5', 'IMG_5.jpg', 'ambiguous', ['CERT3', 'CERT4']),
    photo('6', 'IMG_6.jpg', 'none'),
    photo('7', 'IMG_7.jpg', 'found', ['CERT7']),
  ]

  assert.deepEqual(suggestPairs(photos).map(({ frontId, backId, certId }) => ({ frontId, backId, certId })), [
    { frontId: '1', backId: '2', certId: 'CERT1' },
    { frontId: '6', backId: '7', certId: 'CERT7' },
  ])
})

test('a back QR uses its previous image even if the previous scan failed or contains a QR', () => {
  const photos = [photo('a', 'IMG_1.jpg', 'error'), photo('b', 'IMG_2.jpg', 'found', ['CERT1']), photo('c', 'IMG_3.jpg', 'found', ['FRONT']), photo('d', 'IMG_4.jpg', 'found', ['CERT2'])]
  assert.deepEqual(suggestPairs(photos).map(p => [p.frontId, p.backId, p.certId]), [['a', 'b', 'CERT1'], ['c', 'd', 'CERT2']])
})

test('validatePairs accepts an already named no-op pair', () => {
  const photos = [
    photo('front', 'AbC123_A.webp', 'none'),
    photo('back', 'AbC123_B.webp', 'found', ['AbC123']),
  ]
  const pair = autoPair('pair', 'front', 'back', 'AbC123')
  assert.deepEqual(validatePairs(photos, [pair], photos.map((item) => item.name)), new Map())
})

test('validatePairs marks every row involved in reused sources, duplicate certs and case-insensitive targets', () => {
  const photos = [
    photo('a', 'one.jpg', 'none'),
    photo('b', 'two.jpg', 'found', ['CERT1']),
    photo('c', 'three.jpg', 'none'),
  ]
  const pairs: Pair[] = [
    autoPair('p1', 'a', 'b', 'CERT1'),
    autoPair('p2', 'c', 'b', 'cert1'),
  ]
  const errors = validatePairs(photos, pairs, ['one.jpg', 'two.jpg', 'three.jpg', 'CERT1_A.JPG'])

  assert.match(errors.get('p1')?.join(' ') ?? '', /重复使用|证书号|目标文件名/)
  assert.match(errors.get('p2')?.join(' ') ?? '', /重复使用|证书号|目标文件名/)
})

test('fixed-order results require a strict ID matching the decoded back QR', () => {
  const photos = [photo('a', 'one.png', 'error'), photo('b', 'two.png', 'found', ['Correct123'])]
  const good: Pair = { id: 'good', frontId: 'a', backId: 'b', certId: 'Correct123', selected: true }
  const bad: Pair = { ...good, id: 'bad', certId: '../bad' }
  const whitespace: Pair = { ...good, id: 'whitespace', certId: ' Correct123' }
  const goodErrors = validatePairs(photos, [good], photos.map((item) => item.name))
  const badErrors = validatePairs(photos, [bad], photos.map((item) => item.name))
  const whitespaceErrors = validatePairs(photos, [whitespace], photos.map((item) => item.name))

  assert.equal(goodErrors.has('good'), false)
  assert.match(badErrors.get('bad')?.join(' ') ?? '', /ASCII 字母或数字/)
  assert.match(whitespaceErrors.get('whitespace')?.join(' ') ?? '', /ASCII 字母或数字/)
  const changed = validatePairs(photos, [{ ...good, certId: 'Different123' }], photos.map(item => item.name))
  assert.match(changed.get('good')?.join(' ') ?? '', /二维码不一致/)
})

test('validatePairs always reserves WebP targets and catches case variants', () => {
  const photos = [photo('a', 'front.JPEG', 'none'), photo('b', 'back.JPEG', 'found', ['CERT9'])]
  const errors = validatePairs(photos, [autoPair('jpeg', 'a', 'b', 'CERT9')], [
    'front.JPEG',
    'back.JPEG',
    'cert9_a.WEBP',
  ])
  assert.match(errors.get('jpeg')?.join(' ') ?? '', /CERT9_A\.webp/)
})

test('a legacy same-cert JPEG is a source, not a collision with its WebP output', () => {
  const photos = [
    photo('a', 'CERT9_A.jpg', 'none'),
    photo('b', 'CERT9_B.jpg', 'found', ['CERT9']),
  ]
  assert.deepEqual(
    validatePairs(photos, [autoPair('jpeg-to-webp', 'a', 'b', 'CERT9')], photos.map((item) => item.name)),
    new Map(),
  )
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

function autoPair(id: string, frontId: string, backId: string, certId: string): Pair {
  return { id, frontId, backId, certId, selected: true }
}
