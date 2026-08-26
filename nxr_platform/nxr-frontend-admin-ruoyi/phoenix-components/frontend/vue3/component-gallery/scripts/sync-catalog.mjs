/* global process, console */
import { readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const componentRoot = resolve(here, '..')
const repositoryIndex = resolve(componentRoot, '../../..', 'components.index.json')
const repositoryRequests = resolve(componentRoot, '../../..', 'component-requests.json')
const output = resolve(componentRoot, 'src/data/components.json')
const requestOutput = resolve(componentRoot, 'src/data/component-requests.json')
const localePath = resolve(componentRoot, 'src/data/catalog.zh-CN.json')
const check = process.argv.includes('--check')

const source = JSON.parse(await readFile(repositoryIndex, 'utf8'))
if (!source || !Array.isArray(source.components)) throw new Error('components.index.json must contain a components array')
const locale = JSON.parse(await readFile(localePath, 'utf8'))
if (!locale || Array.isArray(locale) || typeof locale !== 'object') throw new Error('catalog.zh-CN.json must be an object')

const sourceIds = new Set(source.components.map((item) => item.id))
const localeIds = new Set(Object.keys(locale))
const missingTranslations = [...sourceIds].filter((id) => !localeIds.has(id))
const staleTranslations = [...localeIds].filter((id) => !sourceIds.has(id))
if (missingTranslations.length || staleTranslations.length) {
  throw new Error(`Chinese catalog translations must match the root index exactly; missing: ${missingTranslations.join(', ') || 'none'}; stale: ${staleTranslations.join(', ') || 'none'}`)
}

const ids = new Set()
const components = source.components.map((item) => {
  if (!item || typeof item.id !== 'string' || ids.has(item.id)) throw new Error('component ids must be non-empty and unique')
  ids.add(item.id)
  const translated = locale[item.id]
  if (!translated || typeof translated.name !== 'string' || !translated.name.trim() || typeof translated.summary !== 'string' || !translated.summary.trim()) {
    throw new Error(`catalog translation must contain a non-empty name and summary: ${item.id}`)
  }
  return {
    id: item.id,
    name: translated.name,
    version: item.version,
    category: item.category,
    stack: item.stack,
    status: item.status,
    kind: item.kind,
    capabilities: item.capabilities,
    owner: item.owner,
    keywords: item.keywords,
    summary: translated.summary,
    path: item.path,
    compatibility: item.compatibility,
    dependencies: item.dependencies,
  }
}).sort((left, right) => `${left.category}/${left.stack}/${left.name}`.localeCompare(`${right.category}/${right.stack}/${right.name}`))

const expected = `${JSON.stringify(components, null, 2)}\n`
const requests = JSON.parse(await readFile(repositoryRequests, 'utf8'))
if (!requests || requests.schemaVersion !== 1 || !Array.isArray(requests.requests)) {
  throw new Error('component-requests.json must contain schemaVersion 1 and a requests array')
}
const expectedRequests = `${JSON.stringify(requests, null, 2)}\n`
if (check) {
  const current = await readFile(output, 'utf8')
  if (current !== expected) throw new Error('component gallery catalog snapshot is stale; run npm run catalog:sync')
  const currentRequests = await readFile(requestOutput, 'utf8')
  if (currentRequests !== expectedRequests) throw new Error('component request snapshot is stale; run npm run catalog:sync')
} else {
  await writeFile(output, expected)
  await writeFile(requestOutput, expectedRequests)
}

console.log(`${check ? 'checked' : 'wrote'} ${components.length} catalog entries and ${requests.requests.length} component requests`)
