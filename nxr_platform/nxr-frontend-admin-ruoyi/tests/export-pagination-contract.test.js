import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { parse } from '@vue/compiler-sfc'

const exportsPage = parse(
  readFileSync(new URL('../src/views/nxr/exports/index.vue', import.meta.url), 'utf8')
).descriptor
const paginationComponent = parse(
  readFileSync(new URL('../src/components/ExportPagination/index.vue', import.meta.url), 'utf8')
).descriptor

test('export preview never advertises a page size above its backend contract', () => {
  const paginationUses = [...exportsPage.template.content.matchAll(/<export-pagination\b[\s\S]*?\/>/g)].map(match => match[0])

  assert.equal(paginationUses.length, 2)
  assert.match(exportsPage.scriptSetup.content, /const previewPageSizes = \[10, 20, 50, 100\]/)
  assert.match(paginationUses[0], /:page-sizes="previewPageSizes"/)
  assert.doesNotMatch(paginationUses[1], /:page-sizes=/)
  assert.match(
    paginationComponent.scriptSetup.content,
    /default:\s*\(\)\s*=>\s*\[10, 20, 50, 100, 200\]/
  )
})
