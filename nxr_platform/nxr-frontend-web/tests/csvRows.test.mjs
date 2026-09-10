import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'
const source = await readFile(new URL('../src/lib/csvRows.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
const { csvRows } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
test('Excel BOM, commas, escaped quotes and multiline customer notes retain their cells', () => {
  assert.deepEqual(csvRows('\uFEFFref,name,note\r\nA,"Doe, John","A ""rare"" card\nKeep separate"\r\nB,Name,\r\n'), [
    ['ref', 'name', 'note'], ['A', 'Doe, John', 'A "rare" card\nKeep separate'], ['B', 'Name', ''],
  ])
})
test('broken quoted rows are rejected instead of silently creating wrong customer orders', () => {
  assert.throws(() => csvRows('A,"Card\nB,Other'), /not closed/)
  assert.throws(() => csvRows('A,"Card"another,B'), /Unexpected/)
})
