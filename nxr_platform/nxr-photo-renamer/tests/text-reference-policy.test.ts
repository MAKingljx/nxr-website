import assert from 'node:assert/strict'
import test from 'node:test'
import {
  classifyTextReference,
  extractTextReferenceCandidates,
  normalizeCertificateInput,
  normalizeReferenceText,
} from '../src/lib/text-reference-policy.ts'

test('normalizes only safe full-width and horizontal digit spacing', () => {
  assert.equal(normalizeReferenceText('　０１２ ３４５　'), '012345')
  assert.equal(normalizeReferenceText('012\n345'), '012\n345')
  assert.equal(normalizeReferenceText('O0 I1'), 'O0 I1')
  assert.equal(normalizeReferenceText('①23'), '①23')
})

test('accepts a normalized certificate or exact NXR card link without guessing', () => {
  assert.equal(normalizeCertificateInput(' ００１ ２３４ '), '001234')
  assert.equal(normalizeCertificateInput('https://nxrgrading.com/card/VRA003'), 'VRA003')
  assert.equal(normalizeCertificateInput('https://evil.example/card/VRA003'), null)
  assert.equal(normalizeCertificateInput('O0-I1'), null)
})

test('extracts conservative candidates with digits and ignores brand words', () => {
  assert.deepEqual(
    extractTextReferenceCandidates('Pokemon NXR 643 661 7959\nREFERENCE9'),
    ['6436617959', 'REFERENCE9'],
  )
})

test('classifies OCR as a silent reference while the QR remains authoritative', () => {
  assert.equal(classifyTextReference('8630289503', '8630289503').state, 'matched')
  assert.equal(classifyTextReference('8630289508', '8630289503').state, 'mismatch')
  assert.equal(classifyTextReference('8630289503').state, 'reference')
  assert.equal(classifyTextReference('Pokemon NXR').state, 'unreadable')
})
