import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { beforeAll, describe, expect, it } from 'vitest'

interface MetricValue {
  absolute: number
  percent: number
}

interface BenchmarkReport {
  schemaVersion: number
  scenario: {
    id: string
    contract: { capabilities: string[]; props: string[]; events: string[] }
  }
  methodology: { measures: string[]; scope: string; caveats: string[] }
  variants: Record<string, {
    path: string
    contractVerified: boolean
    metrics: Record<string, number>
  }>
  comparison: Record<string, MetricValue>
  conclusion: { supported: boolean; summary: string }
  recommendations: string[]
}

const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const script = resolve(packageRoot, 'scripts/measure-development-efficiency.mjs')
const rawSource = resolve(packageRoot, 'benchmarks/development-efficiency/raw-user-management.vue')
const phoenixSource = resolve(packageRoot, 'benchmarks/development-efficiency/phoenix-user-management.vue')
const evidence = resolve(packageRoot, 'evidence/development-efficiency.json')

function runBenchmark() {
  return execFileSync(process.execPath, [script], { cwd: packageRoot, encoding: 'utf8' })
}

describe('Phoenix 用户管理页开发效率基准', () => {
  let output = ''
  let report: BenchmarkReport

  beforeAll(() => {
    output = runBenchmark()
    report = JSON.parse(output) as BenchmarkReport
  })

  it('测量脚本可直接运行并输出 JSON', () => {
    expect(() => JSON.parse(output)).not.toThrow()
    expect(report.schemaVersion).toBe(1)
  })

  it('相同输入连续测量得到确定结果', () => {
    expect(runBenchmark()).toBe(output)
  })

  it('提交的效率证据与当前测量结果一致', () => {
    expect(JSON.parse(readFileSync(evidence, 'utf8'))).toEqual(report)
  })

  it('报告保持稳定的顶层字段', () => {
    expect(Object.keys(report)).toEqual([
      'schemaVersion',
      'scenario',
      'methodology',
      'variants',
      'comparison',
      'conclusion',
      'recommendations',
    ])
  })

  it('固定场景包含全部八项必需能力', () => {
    expect(report.scenario.id).toBe('user-management-page')
    expect(report.scenario.contract.capabilities).toEqual([
      'title', 'metrics', 'query', 'batch', 'table', 'create', 'import', 'export',
    ])
  })

  it('raw 与 Phoenix 版本通过同一 props 和事件契约校验', () => {
    expect(report.scenario.contract.props).toEqual(['rows', 'stats', 'queryModel', 'selectedKeys', 'busy'])
    expect(report.scenario.contract.events).toContain('update:selectedKeys')
    expect(report.variants.rawVue.contractVerified).toBe(true)
    expect(report.variants.phoenixComponents.contractVerified).toBe(true)
  })

  it('所有约定的测量值都是非负整数', () => {
    for (const variant of Object.values(report.variants)) {
      expect(Object.keys(variant.metrics)).toEqual(['nonEmptySourceLines', 'templateLines', 'styleLines', 'utf8Bytes'])
      for (const value of Object.values(variant.metrics)) {
        expect(Number.isInteger(value)).toBe(true)
        expect(value).toBeGreaterThanOrEqual(0)
      }
    }
  })

  it('Phoenix 复用版明显减少消费者自写非空源码行', () => {
    expect(report.comparison.nonEmptySourceLines.absolute).toBeGreaterThan(40)
    expect(report.comparison.nonEmptySourceLines.percent).toBeGreaterThan(30)
  })

  it('Phoenix 复用版无需新增页面样式', () => {
    expect(report.variants.rawVue.metrics.styleLines).toBeGreaterThan(40)
    expect(report.variants.phoenixComponents.metrics.styleLines).toBe(0)
    expect(report.comparison.styleLines.percent).toBe(100)
  })

  it('Phoenix 复用版同时减少模板行和 UTF-8 字节', () => {
    expect(report.comparison.templateLines.absolute).toBeGreaterThan(20)
    expect(report.comparison.utf8Bytes.absolute).toBeGreaterThan(2_000)
  })

  it('两个源码文件携带完全一致的能力标记', () => {
    const marker = /<!-- benchmark-capabilities: ([^>]+) -->/u
    const raw = readFileSync(rawSource, 'utf8').match(marker)?.[1]
    const phoenix = readFileSync(phoenixSource, 'utf8').match(marker)?.[1]
    expect(raw).toBeDefined()
    expect(phoenix).toBe(raw)
  })

  it('报告明确限制结论边界且不声称人时或 token 节省', () => {
    expect(report.methodology.caveats.join('')).toContain('不等同于真实开发人时')
    expect(report.methodology.caveats.join('')).toContain('不估算或宣称精确 token 节省')
    expect(report.conclusion.summary).toContain('仅说明该场景')
  })

  it('报告给出不越界修改源码的 DX 建议', () => {
    expect(report.recommendations).toHaveLength(2)
    expect(report.recommendations.join('')).toContain('受控事件转发')
  })
})
