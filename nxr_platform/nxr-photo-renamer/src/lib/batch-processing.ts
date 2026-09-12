/** Bounded loading preserves file order without making thousands of active promises. */
export async function mapConcurrent<T, R>(items: T[], concurrency: number, operation: (item: T, index: number) => Promise<R>): Promise<R[]> {
  const results: R[] = new Array(items.length)
  let next = 0
  let failure: unknown
  let failed = false
  const lanes = Number.isFinite(concurrency) ? Math.max(1, Math.floor(concurrency)) : 1
  await Promise.all(Array.from({ length: Math.min(lanes, items.length) }, async () => {
    while (!failed && next < items.length) {
      const index = next++
      try { results[index] = await operation(items[index]!, index) }
      catch (error) { failed = true; failure ??= error }
    }
  }))
  if (failed) throw failure
  return results
}
