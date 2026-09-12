export type ProcessingKind = 'scan' | 'webp' | 'thumbnail'

export interface DeviceCapabilities {
  hardwareConcurrency?: number
  deviceMemory?: number
}

export interface RuntimePerformanceMetrics {
  workingSetBytes?: number
  privateBytes?: number
  measuredAtMs?: number
}

export interface ThroughputWindow {
  throughputPerSecond?: number
  averageLatencyMs?: number
}

export interface AdaptiveGovernorOptions {
  deviceMemoryGiB?: number
  initialConcurrency?: number
  metricsReader?: () => Promise<RuntimePerformanceMetrics | undefined>
  sampleIntervalMs?: number
  recoverySamples?: number
  throughputWindowSize?: number
}

interface ActiveTask {
  estimatedBytes: number
  startedAtMs: number
}

interface ThroughputTrial {
  previousLimit: number
  regressionThroughput: number
  regressionLatency: number
}

const GIB = 1024 ** 3
const MEMORY_SHARE = 0.5
const LANE_MEMORY_GIB: Record<ProcessingKind, number> = {
  scan: 0.75,
  webp: 1,
  thumbnail: 0.5,
}
const MIN_SAMPLE_INTERVAL_MS = 500
const MAX_SAMPLE_INTERVAL_MS = 10_000
const DEFAULT_SAMPLE_INTERVAL_MS = 1_500
const DEFAULT_RECOVERY_SAMPLES = 2

/**
 * Return the static CPU and memory budget. Every logical core is available
 * when it fits inside the half-memory image-processing allowance.
 */
export function getProcessingConcurrency(
  kind: ProcessingKind,
  capabilities: DeviceCapabilities = currentDeviceCapabilities(),
): number {
  const cores = positiveNumber(capabilities.hardwareConcurrency)
  const memory = positiveNumber(capabilities.deviceMemory)
  const coreLanes = cores === undefined ? 2 : Math.floor(cores)
  const memoryLanes = memory === undefined
    ? 2
    : Math.max(1, Math.floor((memory * MEMORY_SHARE) / LANE_MEMORY_GIB[kind]))
  return Math.max(1, Math.min(coreLanes, memoryLanes))
}

/**
 * Permit QR scanning to explore beyond the normal half-memory budget only on
 * devices with at least two GiB per logical core. The governor still starts at
 * the normal scan budget and must earn this ceiling from healthy observations.
 */
export function getScanSearchCeiling(
  capabilities: DeviceCapabilities = currentDeviceCapabilities(),
): number {
  const normalBudget = getProcessingConcurrency('scan', capabilities)
  const cores = positiveNumber(capabilities.hardwareConcurrency)
  const memory = positiveNumber(capabilities.deviceMemory)
  if (cores === undefined || memory === undefined || cores < 4 || memory < cores * 2) {
    return normalBudget
  }
  const searchBudget = Math.floor((memory * 0.6) / LANE_MEMORY_GIB.scan)
  return Math.max(normalBudget, Math.min(Math.floor(cores) * 2, searchBudget))
}

/** Estimate decoded image and codec scratch space without reading image bytes. */
export function estimateTaskFootprint(kind: ProcessingKind, fileSize: number): number {
  const size = positiveNumber(fileSize) ?? 0
  const estimate = kind === 'webp'
    ? Math.max(96 * 1024 ** 2, size * 18)
    : kind === 'scan'
      ? Math.max(48 * 1024 ** 2, size * 12)
      : Math.max(32 * 1024 ** 2, size * 8)
  return Math.min(estimate, kind === 'webp' ? 1536 * 1024 ** 2 : 768 * 1024 ** 2)
}

/**
 * Additive recovery and conservative multiplicative decrease for one worker
 * pool. Running work is only accounted for; callers release idle slots after a
 * limit change and never need to interrupt tasks.
 */
export class AdaptiveConcurrencyGovernor {
  readonly maxConcurrency: number

  private readonly initialConcurrency: number
  private limit: number
  private readonly totalMemoryBytes?: number
  private readonly metricsReader?: () => Promise<RuntimePerformanceMetrics | undefined>
  private readonly sampleIntervalMs: number
  private readonly recoverySamples: number
  private readonly throughputWindowSize: number
  private readonly activeTasks = new Map<number | string, ActiveTask>()
  private memoryBytes?: number
  private memoryConstrained = false
  private memoryRecoveryDebt = 0
  private healthyMemorySamples = 0
  private healthyThroughputWindows = 0
  private bestThroughput?: number
  private bestLatency?: number
  private throughputTrial?: ThroughputTrial
  private throughputCooldown = 0
  private windowStartedAtMs?: number
  private windowCompleted = 0
  private windowLatencyMs = 0
  private lastSampleStartedAtMs = Number.NEGATIVE_INFINITY
  private sampleInFlight?: Promise<void>

  constructor(maxConcurrency: number, options: AdaptiveGovernorOptions = {}) {
    this.maxConcurrency = normaliseLimit(maxConcurrency)
    this.initialConcurrency = clamp(
      normaliseLimit(options.initialConcurrency ?? this.maxConcurrency),
      1,
      this.maxConcurrency,
    )
    this.limit = this.initialConcurrency
    const memoryGiB = positiveNumber(options.deviceMemoryGiB)
    this.totalMemoryBytes = memoryGiB === undefined ? undefined : memoryGiB * GIB
    this.metricsReader = options.metricsReader
    this.sampleIntervalMs = clamp(
      positiveNumber(options.sampleIntervalMs) ?? DEFAULT_SAMPLE_INTERVAL_MS,
      MIN_SAMPLE_INTERVAL_MS,
      MAX_SAMPLE_INTERVAL_MS,
    )
    this.recoverySamples = Math.max(1, Math.floor(
      positiveNumber(options.recoverySamples) ?? DEFAULT_RECOVERY_SAMPLES,
    ))
    this.throughputWindowSize = Math.max(2, Math.floor(
      positiveNumber(options.throughputWindowSize)
        ?? Math.min(12, Math.max(4, this.maxConcurrency)),
    ))
  }

  getConcurrency(): number {
    return clamp(Math.floor(this.limit), 1, this.maxConcurrency)
  }

  taskStarted(id: number | string, estimatedBytes: number, nowMs = monotonicNow()): void {
    const startedAtMs = finiteNumber(nowMs) ?? monotonicNow()
    this.activeTasks.set(id, {
      estimatedBytes: positiveNumber(estimatedBytes) ?? 0,
      startedAtMs,
    })
    if (this.windowStartedAtMs === undefined) this.windowStartedAtMs = startedAtMs
    this.applyMemoryPressure('active-increase')
  }

  taskFinished(id: number | string, nowMs = monotonicNow()): void {
    const task = this.activeTasks.get(id)
    if (!task) return
    this.activeTasks.delete(id)
    const completedAtMs = finiteNumber(nowMs) ?? monotonicNow()
    const latencyMs = Math.max(0, completedAtMs - task.startedAtMs)
    this.windowCompleted += 1
    this.windowLatencyMs += latencyMs

    if (this.windowCompleted >= this.throughputWindowSize && this.windowStartedAtMs !== undefined) {
      const elapsedMs = Math.max(1, completedAtMs - this.windowStartedAtMs)
      this.observeThroughputWindow({
        throughputPerSecond: this.windowCompleted * 1_000 / elapsedMs,
        averageLatencyMs: this.windowLatencyMs / this.windowCompleted,
      })
      this.windowCompleted = 0
      this.windowLatencyMs = 0
      this.windowStartedAtMs = this.activeTasks.size > 0 ? completedAtMs : undefined
    }
    this.applyMemoryPressure('release')
  }

  taskCancelled(id: number | string): void {
    this.activeTasks.delete(id)
    this.applyMemoryPressure('release')
  }

  updateRuntimeMetrics(metrics: RuntimePerformanceMetrics | undefined): void {
    const workingSet = positiveNumber(metrics?.workingSetBytes)
    const privateBytes = positiveNumber(metrics?.privateBytes)
    if (workingSet === undefined && privateBytes === undefined) return
    // Private bytes exclude reclaimable/shared mappings. Fall back to the
    // working set only on hosts that do not report a private footprint.
    this.memoryBytes = privateBytes ?? workingSet
    this.applyMemoryPressure('metrics')
  }

  observeThroughputWindow(window: ThroughputWindow): void {
    const throughput = positiveNumber(window.throughputPerSecond)
    const latency = positiveNumber(window.averageLatencyMs)
    if (throughput === undefined || latency === undefined) return

    if (this.throughputTrial) {
      const trial = this.throughputTrial
      this.throughputTrial = undefined
      const previousPerLane = trial.regressionThroughput / trial.previousLimit
      const trialPerLane = throughput / this.getConcurrency()
      const improved = trialPerLane >= previousPerLane * 1.08
        || (throughput >= trial.regressionThroughput * 1.02
          && latency <= trial.regressionLatency)
      if (!improved) this.limit = Math.min(this.maxConcurrency, trial.previousLimit)
      this.bestThroughput = throughput
      this.bestLatency = latency
      this.healthyThroughputWindows = 0
      this.throughputCooldown = 2
      return
    }

    if (this.throughputCooldown > 0) {
      this.throughputCooldown -= 1
      this.updateThroughputBaseline(throughput, latency)
      return
    }

    if (this.bestThroughput !== undefined && this.bestLatency !== undefined
      && throughput < this.bestThroughput * 0.75
      && latency > this.bestLatency * 1.25
      && !this.memoryConstrained
      && this.limit > 1) {
      this.throughputTrial = {
        previousLimit: this.limit,
        regressionThroughput: throughput,
        regressionLatency: latency,
      }
      this.limit -= 1
      this.healthyThroughputWindows = 0
      return
    }

    this.updateThroughputBaseline(throughput, latency)
    this.healthyThroughputWindows += 1
    this.tryRecover()
  }

  /** Demand-driven sampling avoids a background timer when no work is active. */
  refreshRuntimeMetrics(nowMs = monotonicNow()): Promise<void> {
    if (!this.metricsReader) return Promise.resolve()
    const now = finiteNumber(nowMs) ?? monotonicNow()
    if (this.sampleInFlight) return this.sampleInFlight
    if (now - this.lastSampleStartedAtMs < this.sampleIntervalMs) return Promise.resolve()
    this.lastSampleStartedAtMs = now
    this.sampleInFlight = Promise.resolve()
      .then(() => this.metricsReader?.())
      .then(metrics => this.updateRuntimeMetrics(metrics))
      .catch(() => undefined)
      .finally(() => { this.sampleInFlight = undefined })
    return this.sampleInFlight
  }

  reset(): void {
    this.limit = this.initialConcurrency
    this.activeTasks.clear()
    this.memoryBytes = undefined
    this.memoryConstrained = false
    this.memoryRecoveryDebt = 0
    this.healthyMemorySamples = 0
    this.healthyThroughputWindows = 0
    this.bestThroughput = undefined
    this.bestLatency = undefined
    this.throughputTrial = undefined
    this.throughputCooldown = 0
    this.windowStartedAtMs = undefined
    this.windowCompleted = 0
    this.windowLatencyMs = 0
  }

  private applyMemoryPressure(source: 'metrics' | 'active-increase' | 'release'): void {
    if (this.totalMemoryBytes === undefined) return
    const activeEstimate = Array.from(this.activeTasks.values())
      .reduce((total, task) => total + task.estimatedBytes, 0)
    const observed = this.memoryBytes ?? 0
    if (observed === 0 && activeEstimate === 0) {
      if (this.memoryConstrained) this.recordHealthyMemorySample()
      return
    }
    // The process working set usually already includes decoded task memory.
    // Taking the stronger signal avoids double-counting the same image buffers.
    const pressure = Math.max(observed, activeEstimate) / this.totalMemoryBytes

    if (pressure >= 0.75) {
      if (source === 'metrics' || (source === 'active-increase' && activeEstimate >= observed)) {
        const previousLimit = this.limit
        this.limit = Math.max(1, Math.floor(this.limit / 2))
        this.memoryRecoveryDebt += previousLimit - this.limit
      }
      this.memoryConstrained = true
      this.throughputTrial = undefined
      this.healthyMemorySamples = 0
      return
    }
    if (pressure >= 0.6) {
      if (source === 'metrics' || (source === 'active-increase' && activeEstimate >= observed)) {
        const previousLimit = this.limit
        this.limit = Math.max(1, Math.min(this.limit - 1, Math.floor(this.limit * 0.75)))
        this.memoryRecoveryDebt += previousLimit - this.limit
      }
      this.memoryConstrained = true
      this.throughputTrial = undefined
      this.healthyMemorySamples = 0
      return
    }
    if (pressure <= 0.45) {
      this.recordHealthyMemorySample()
    } else {
      this.healthyMemorySamples = 0
    }
  }

  private recordHealthyMemorySample(): void {
    if ((!this.memoryConstrained && this.memoryRecoveryDebt === 0) || this.throughputTrial) return
    this.healthyMemorySamples += 1
    if (this.healthyMemorySamples < this.recoverySamples) return
    this.healthyMemorySamples = 0
    if (this.memoryRecoveryDebt > 0) {
      const step = Math.min(this.memoryRecoveryDebt, Math.max(1, Math.ceil(this.maxConcurrency / 8)))
      const previousLimit = this.limit
      this.limit = Math.min(this.maxConcurrency, this.limit + step)
      this.memoryRecoveryDebt = Math.max(0, this.memoryRecoveryDebt - (this.limit - previousLimit))
    }
    this.memoryConstrained = this.memoryRecoveryDebt > 0
  }

  private tryRecover(): void {
    if (this.memoryConstrained || this.healthyThroughputWindows < this.recoverySamples) return
    this.healthyThroughputWindows = 0
    this.increaseLimit()
  }

  private updateThroughputBaseline(throughput: number, latency: number): void {
    this.bestThroughput = this.bestThroughput === undefined
      ? throughput
      : this.bestThroughput * 0.8 + throughput * 0.2
    this.bestLatency = this.bestLatency === undefined
      ? latency
      : this.bestLatency * 0.8 + latency * 0.2
  }

  private increaseLimit(): void {
    if (this.limit >= this.maxConcurrency) return
    const step = Math.max(1, Math.ceil(this.maxConcurrency / 8))
    this.limit = Math.min(this.maxConcurrency, this.limit + step)
  }
}

export function createAdaptiveGovernor(
  kind: ProcessingKind,
  maxConcurrency = getProcessingConcurrency(kind),
  options: AdaptiveGovernorOptions = {},
): AdaptiveConcurrencyGovernor {
  const capabilities = currentDeviceCapabilities()
  return new AdaptiveConcurrencyGovernor(maxConcurrency, {
    deviceMemoryGiB: options.deviceMemoryGiB ?? capabilities.deviceMemory,
    initialConcurrency: options.initialConcurrency,
    metricsReader: options.metricsReader ?? readDesktopPerformanceMetrics,
    sampleIntervalMs: options.sampleIntervalMs,
    recoverySamples: options.recoverySamples,
    throughputWindowSize: options.throughputWindowSize,
  })
}

function currentDeviceCapabilities(): DeviceCapabilities {
  const desktop = (globalThis as typeof globalThis & {
    nxrDesktop?: { capabilities?: DeviceCapabilities }
  }).nxrDesktop?.capabilities
  if (desktop) {
    return {
      hardwareConcurrency: desktop.hardwareConcurrency,
      deviceMemory: desktop.deviceMemory,
    }
  }
  if (typeof navigator === 'undefined') return {}
  const browserNavigator = navigator as Navigator & { deviceMemory?: number }
  return {
    hardwareConcurrency: browserNavigator.hardwareConcurrency,
    deviceMemory: browserNavigator.deviceMemory,
  }
}

async function readDesktopPerformanceMetrics(): Promise<RuntimePerformanceMetrics | undefined> {
  const reader = (globalThis as typeof globalThis & {
    nxrDesktop?: { getPerformanceMetrics?: () => Promise<RuntimePerformanceMetrics> }
  }).nxrDesktop?.getPerformanceMetrics
  if (!reader) return undefined
  const metrics = await reader()
  if (!metrics || typeof metrics !== 'object') return undefined
  return {
    workingSetBytes: positiveNumber(metrics.workingSetBytes),
    privateBytes: positiveNumber(metrics.privateBytes),
    measuredAtMs: finiteNumber(metrics.measuredAtMs),
  }
}

function monotonicNow(): number {
  return typeof performance === 'undefined' ? Date.now() : performance.now()
}

function normaliseLimit(value: number): number {
  const valid = positiveNumber(value)
  return Math.max(1, valid === undefined ? 1 : Math.floor(valid))
}

function positiveNumber(value: number | undefined): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : undefined
}

function finiteNumber(value: number | undefined): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(maximum, Math.max(minimum, value))
}
