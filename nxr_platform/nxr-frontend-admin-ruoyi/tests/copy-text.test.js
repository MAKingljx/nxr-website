import test from 'node:test'
import assert from 'node:assert/strict'
import { copyText } from '../src/utils/copyText.js'

function documentStub(result) {
  const calls = []
  const input = { value: '', style: {}, setAttribute() {}, select() { calls.push('select') }, remove() { calls.push('remove') } }
  return { calls, input, body: { appendChild(node) { assert.equal(node, input); calls.push('append') } }, activeElement: { focus() { calls.push('focus') } }, createElement() { return input }, execCommand(command) { assert.equal(command, 'copy'); return result } }
}

test('uses the clipboard API when available', async () => {
  let copied
  assert.equal(await copyText('label', { async writeText(value) { copied = value } }, null), true)
  assert.equal(copied, 'label')
})

test('copies with the legacy command when the clipboard API is unavailable or rejects', async () => {
  for (const clipboard of [null, { async writeText() { throw new Error('denied') } }]) {
    const doc = documentStub(true)
    assert.equal(await copyText('label', clipboard, doc), true)
    assert.equal(doc.input.value, 'label')
    assert.deepEqual(doc.calls, ['append', 'select', 'remove', 'focus'])
  }
})

test('reports failure when copy is rejected and the legacy command fails', async () => {
  assert.equal(await copyText('label', null, documentStub(false)), false)
  assert.equal(await copyText('label', null, null), false)
})
