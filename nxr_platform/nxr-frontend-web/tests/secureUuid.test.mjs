import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

const source = await readFile(new URL('../src/lib/secureUuid.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
const { secureUuid } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)

test('uses the native UUID generator when available', () => {
  const random = { randomUUID() { assert.equal(this, random); return 'native-id' }, getRandomValues() { throw new Error('unexpected fallback') } }
  assert.equal(secureUuid(random), 'native-id')
})

test('builds a version 4 UUID from cryptographic bytes when randomUUID is unavailable', () => {
  const random = { getRandomValues(bytes) { assert.equal(this, random); bytes.fill(255); return bytes } }
  assert.equal(secureUuid(random), 'ffffffff-ffff-4fff-bfff-ffffffffffff')
})

test('fails closed when secure randomness is unavailable', () => {
  assert.throws(() => secureUuid({}), /Secure random number generation is unavailable/)
})
