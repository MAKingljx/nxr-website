import { Buffer } from 'node:buffer'
import { readFile } from 'node:fs/promises'
import { dirname, relative, resolve } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const requiredContract = {
  capabilities: ['title', 'metrics', 'query', 'batch', 'table', 'create', 'import', 'export'],
  props: ['rows', 'stats', 'queryModel', 'selectedKeys', 'busy'],
  events: ['update:queryModel', 'update:selectedKeys', 'query', 'reset', 'batch', 'clearSelection', 'create', 'import', 'export'],
}
const sources = {
  rawVue: resolve(packageRoot, 'benchmarks/development-efficiency/raw-user-management.vue'),
  phoenixComponents: resolve(packageRoot, 'benchmarks/development-efficiency/phoenix-user-management.vue'),
}

function nonEmptyLines(source) {
  return source.split(/\r?\n/u).filter((line) => line.trim().length > 0).length
}

function blockLines(source, tag) {
  const opening = source.match(new RegExp(`<${tag}(?:\\s[^>]*)?>`, 'u'))
  if (!opening || opening.index === undefined) return 0
  const contentStart = opening.index + opening[0].length
  const contentEnd = source.lastIndexOf(`</${tag}>`)
  return contentEnd > contentStart ? nonEmptyLines(source.slice(contentStart, contentEnd)) : 0
}

function markerValues(source, name) {
  const match = source.match(new RegExp(`<!--\\s*benchmark-${name}:\\s*([^]*?)\\s*-->`, 'u'))
  if (!match) throw new Error(`缺少 benchmark-${name} 能力标记`)
  return match[1].split(',').map((value) => value.trim()).filter(Boolean)
}

function assertSameValues(actual, expected, label, variant) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${variant} 的 ${label} 标记不符合固定场景契约`)
  }
}

function measure(source) {
  return {
    nonEmptySourceLines: nonEmptyLines(source),
    templateLines: blockLines(source, 'template'),
    styleLines: blockLines(source, 'style'),
    utf8Bytes: Buffer.byteLength(source, 'utf8'),
  }
}

function reduction(rawValue, phoenixValue) {
  const absolute = rawValue - phoenixValue
  return {
    absolute,
    percent: Number(((absolute / rawValue) * 100).toFixed(1)),
  }
}

async function inspectVariant(name, path) {
  const source = await readFile(path, 'utf8')
  const contract = {
    capabilities: markerValues(source, 'capabilities'),
    props: markerValues(source, 'props'),
    events: markerValues(source, 'events'),
  }
  assertSameValues(contract.capabilities, requiredContract.capabilities, 'capabilities', name)
  assertSameValues(contract.props, requiredContract.props, 'props', name)
  assertSameValues(contract.events, requiredContract.events, 'events', name)
  return {
    path: relative(packageRoot, path).split('\\').join('/'),
    contractVerified: true,
    metrics: measure(source),
  }
}

const variants = {}
for (const [name, path] of Object.entries(sources)) {
  variants[name] = await inspectVariant(name, path)
}

const comparison = {}
for (const metric of ['nonEmptySourceLines', 'templateLines', 'styleLines', 'utf8Bytes']) {
  comparison[metric] = reduction(variants.rawVue.metrics[metric], variants.phoenixComponents.metrics[metric])
}

const report = {
  schemaVersion: 1,
  scenario: {
    id: 'user-management-page',
    label: '用户管理页',
    contract: requiredContract,
  },
  methodology: {
    measures: ['消费者需编写的非空源代码行', '模板非空行', '样式非空行', '完整文件 UTF-8 字节'],
    scope: '比较两个功能契约一致、采用同一格式风格的 Vue 单文件组件，不计 Phoenix 组件库内部实现。',
    caveats: ['这是静态源码规模基准，不等同于真实开发人时。', '本基准不估算或宣称精确 token 节省。'],
  },
  variants,
  comparison,
  conclusion: {
    supported: comparison.nonEmptySourceLines.absolute > 0 && comparison.styleLines.absolute > 0,
    summary: '在固定用户管理页场景中，Phoenix 复用版减少了消费者自写源码和样式；结果仅说明该场景的代码规模变化。',
  },
  recommendations: [
    '继续提供页面框架与查询、批量、表格组件的组合示例。',
    '为常见表格页补充更短的受控事件转发约定，可进一步减少消费者胶水代码。',
  ],
}

process.stdout.write(`${JSON.stringify(report, null, 2)}\n`)
