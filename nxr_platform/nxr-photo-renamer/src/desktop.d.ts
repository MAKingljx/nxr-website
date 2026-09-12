interface Window {
  nxrDesktop?: {
    capabilities: { hardwareConcurrency: number; deviceMemory: number }
    getPerformanceMetrics: () => Promise<{
      workingSetBytes: number
      privateBytes: number
      measuredAtMs: number
    }>
    setProcessingBusy: (busy: boolean) => void
  }
}
