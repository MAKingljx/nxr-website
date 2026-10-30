import test from 'node:test'
import assert from 'node:assert/strict'
import { secureUuid } from '../src/utils/secureUuid.js'

test('uses the native UUID generator when available', () => {
  const source = { randomUUID() { return 'native-uuid' }, getRandomValues() { throw new Error('not used') } }
  assert.equal(secureUuid(source), 'native-uuid')
})

test('uses cryptographic bytes when randomUUID is unavailable, preserving UUID v4 format', () => {
  const source = { getRandomValues(bytes) { for (let i = 0; i < bytes.length; i++) bytes[i] = i; return bytes } }
  assert.equal(secureUuid(source), '00010203-0405-4607-8809-0a0b0c0d0e0f')
})

test('refuses to generate an identifier without a cryptographic source', () => {
  assert.throws(() => secureUuid({}), /Secure random/)
})
