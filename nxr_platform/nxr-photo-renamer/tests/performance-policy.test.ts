import assert from 'node:assert/strict'
import test from 'node:test'

import {
  AdaptiveConcurrencyGovernor,
  getProcessingConcurrency,
  getScanSearchCeiling,
} from '../src/lib/performance-policy.ts'

const GIB = 1024 ** 3

test('QR search ceiling expands only on known high-memory devices', () => {
  assert.equal(getProcessingConcurrency('scan', { hardwareConcurrency: 14, deviceMemory: 36 }), 14)
  assert.equal(getScanSearchCeiling({ hardwareConcurrency: 14, deviceMemory: 36 }), 28)
  assert.equal(getScanSearchCeiling({ hardwareConcurrency: 8, deviceMemory: 8 }), 5)
  assert.equal(getScanSearchCeiling({ hardwareConcurrency: 8 }), 2)
  assert.equal(getScanSearchCeiling({ deviceMemory: 32 }), 2)
  assert.equal(getProcessingConcurrency('webp', { hardwareConcurrency: 14, deviceMemory: 36 }), 14)
})

test('a high-memory QR governor starts at the static budget and earns its search ceiling', () => {
  const governor = new AdaptiveConcurrencyGovernor(28, {
    initialConcurrency: 14,
    recoverySamples: 2,
  })
  assert.equal(governor.getConcurrency(), 14)
  for (let index = 0; index < 8; index += 1) {
    governor.observeThroughputWindow({ throughputPerSecond: 100, averageLatencyMs: 50 })
  }
  assert.equal(governor.getConcurrency(), 28)
  governor.reset()
  assert.equal(governor.getConcurrency(), 14)
})

test('memory pressure shrinks active capacity and healthy samples recover the full budget gradually', () => {
  const governor = new AdaptiveConcurrencyGovernor(8, {
    deviceMemoryGiB: 8,
    recoverySamples: 2,
  })
  assert.equal(governor.getConcurrency(), 8)
  governor.updateRuntimeMetrics({ workingSetBytes: 6.2 * GIB })
  assert.equal(governor.getConcurrency(), 4)

  const recovered: number[] = []
  for (let index = 0; index < 8; index += 1) {
    governor.updateRuntimeMetrics({ workingSetBytes: 2 * GIB })
    recovered.push(governor.getConcurrency())
  }
  assert.deepEqual(recovered, [4, 5, 5, 6, 6, 7, 7, 8])
})

test('throughput regression with rising latency backs off then stable windows restore capacity', () => {
  const governor = new AdaptiveConcurrencyGovernor(8, { recoverySamples: 2 })
  governor.observeThroughputWindow({ throughputPerSecond: 100, averageLatencyMs: 50 })
  governor.observeThroughputWindow({ throughputPerSecond: 50, averageLatencyMs: 100 })
  assert.equal(governor.getConcurrency(), 7)

  for (let index = 0; index < 5; index += 1) {
    governor.observeThroughputWindow({ throughputPerSecond: 90, averageLatencyMs: 55 })
  }
  assert.equal(governor.getConcurrency(), 8)
})

test('mixed fast then slow images fail the lower-lane trial and never cascade to one lane', () => {
  const governor = new AdaptiveConcurrencyGovernor(8, { recoverySamples: 2 })
  governor.observeThroughputWindow({ throughputPerSecond: 100, averageLatencyMs: 50 })
  governor.observeThroughputWindow({ throughputPerSecond: 50, averageLatencyMs: 100 })
  assert.equal(governor.getConcurrency(), 7)

  governor.observeThroughputWindow({ throughputPerSecond: 43.75, averageLatencyMs: 100 })
  assert.equal(governor.getConcurrency(), 8, 'no per-lane gain restores the trial concurrency')
  for (let index = 0; index < 12; index += 1) {
    governor.observeThroughputWindow({ throughputPerSecond: 50, averageLatencyMs: 100 })
    assert.ok(governor.getConcurrency() >= 7)
  }
  assert.equal(governor.getConcurrency(), 8)
})

test('healthy memory events cannot overwrite an active throughput trial', () => {
  const governor = new AdaptiveConcurrencyGovernor(8, {
    deviceMemoryGiB: 8,
    recoverySamples: 2,
  })
  governor.updateRuntimeMetrics({ privateBytes: 2 * GIB, measuredAtMs: 1 })
  governor.observeThroughputWindow({ throughputPerSecond: 100, averageLatencyMs: 50 })
  governor.observeThroughputWindow({ throughputPerSecond: 50, averageLatencyMs: 100 })
  assert.equal(governor.getConcurrency(), 7)

  for (let index = 0; index < 4; index += 1) {
    governor.taskStarted(`trial-${index}`, 32 * 1024 ** 2, index * 10)
    governor.taskFinished(`trial-${index}`, index * 10 + 5)
    governor.updateRuntimeMetrics({ privateBytes: 2 * GIB, measuredAtMs: index + 2 })
    assert.equal(governor.getConcurrency(), 7)
  }

  governor.observeThroughputWindow({ throughputPerSecond: 43.75, averageLatencyMs: 100 })
  assert.equal(governor.getConcurrency(), 8, 'the next throughput window alone decides to roll back the trial')
})

test('finishing tasks does not repeatedly consume one cached high-memory sample', () => {
  const governor = new AdaptiveConcurrencyGovernor(8, { deviceMemoryGiB: 8 })
  for (let index = 0; index < 4; index += 1) {
    governor.taskStarted(index, 32 * 1024 ** 2, 0)
  }
  governor.updateRuntimeMetrics({ privateBytes: 6.2 * GIB, measuredAtMs: 1 })
  assert.equal(governor.getConcurrency(), 4)
  for (let index = 0; index < 4; index += 1) governor.taskFinished(index, 100)
  assert.equal(governor.getConcurrency(), 4)
})

test('running image footprint is a pressure signal even before desktop metrics arrive', () => {
  const governor = new AdaptiveConcurrencyGovernor(8, { deviceMemoryGiB: 8 })
  governor.taskStarted('large-image', 6.2 * GIB, 0)
  assert.equal(governor.getConcurrency(), 4)
  governor.taskCancelled('large-image')
  assert.equal(governor.getConcurrency(), 4, 'one cancellation cannot jump directly to full capacity')
  for (let index = 0; index < 8; index += 1) {
    governor.taskStarted(`small-${index}`, 32 * 1024 ** 2, index * 10 + 1)
    governor.taskFinished(`small-${index}`, index * 10 + 2)
  }
  assert.equal(governor.getConcurrency(), 8, 'estimated-only pressure must not leave the pool permanently slow')
})

test('missing, non-finite and hostile observations never break the one-to-maximum bound', () => {
  const governor = new AdaptiveConcurrencyGovernor(12, { deviceMemoryGiB: 16 })
  governor.updateRuntimeMetrics(undefined)
  governor.updateRuntimeMetrics({ workingSetBytes: Number.NaN, privateBytes: Infinity })
  governor.observeThroughputWindow({ throughputPerSecond: Number.NaN, averageLatencyMs: -1 })
  governor.taskStarted('bad', Number.NaN, Number.NaN)
  governor.taskFinished('bad', Number.NaN)
  assert.equal(governor.getConcurrency(), 12)

  governor.updateRuntimeMetrics({ privateBytes: 100 * GIB })
  assert.equal(governor.getConcurrency(), 6)
  governor.updateRuntimeMetrics({ privateBytes: 100 * GIB })
  assert.equal(governor.getConcurrency(), 3)
  governor.updateRuntimeMetrics({ privateBytes: 100 * GIB })
  assert.equal(governor.getConcurrency(), 1)
  governor.updateRuntimeMetrics({ privateBytes: 100 * GIB })
  assert.equal(governor.getConcurrency(), 1)
})

test('runtime metrics sampling is demand-driven, bounded and failure tolerant', async () => {
  let reads = 0
  const governor = new AdaptiveConcurrencyGovernor(4, {
    deviceMemoryGiB: 8,
    sampleIntervalMs: 1_000,
    metricsReader: async () => {
      reads += 1
      if (reads === 2) throw new Error('transient metrics failure')
      return { workingSetBytes: GIB }
    },
  })
  await governor.refreshRuntimeMetrics(0)
  await governor.refreshRuntimeMetrics(999)
  assert.equal(reads, 1)
  await governor.refreshRuntimeMetrics(1_000)
  await governor.refreshRuntimeMetrics(2_000)
  assert.equal(reads, 3)
  assert.equal(governor.getConcurrency(), 4)
})
