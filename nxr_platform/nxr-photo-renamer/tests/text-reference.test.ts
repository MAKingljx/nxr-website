import assert from 'node:assert/strict'
import test from 'node:test'
import {
  cancelTextReference,
  scanTextReference,
  topLabelRegion,
} from '../src/lib/text-reference.ts'

test('top label crop is a broad bounded region above the card body', () => {
  assert.deepEqual(topLabelRegion(4000, 6000), {
    left: 200,
    top: 180,
    width: 3600,
    height: 1860,
  })
  const small = topLabelRegion(1, 1)
  assert.ok(small.left >= 0 && small.top >= 0)
  assert.ok(small.width >= 1 && small.height >= 1)
})

test('cancel rejects queued OCR promises without starting image work', async () => {
  const first = scanTextReference(new File(['image'], 'first.jpg', { type: 'image/jpeg' }))
  const second = scanTextReference(new File(['image'], 'second.jpg', { type: 'image/jpeg' }))
  cancelTextReference()

  await assert.rejects(first, (error: unknown) => error instanceof DOMException && error.name === 'AbortError')
  await assert.rejects(second, (error: unknown) => error instanceof DOMException && error.name === 'AbortError')
})
