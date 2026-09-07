import type { ScanMode } from './scan-policy'

export type PixelTreatment = 'original' | 'contrast-if-low' | 'contrast' | 'local-threshold'

export interface ScanRegion {
  sx: number
  sy: number
  sw: number
  sh: number
  rotation: 0 | 90 | 180 | 270
  maxEdge: number
  kind: 'overview' | 'resampled' | 'coarse' | 'detail' | 'fine' | 'fallback'
  treatment: PixelTreatment
}

const STANDARD_DETAIL_OVERLAP = 0.35
const DEEP_FINE_OVERLAP = 0.45

export function buildScanRegions(width: number, height: number, mode: ScanMode = 'standard'): ScanRegion[] {
  const safeWidth = Math.max(1, Math.floor(width))
  const safeHeight = Math.max(1, Math.floor(height))
  return mode === 'deep'
    ? buildDeepRegions(safeWidth, safeHeight)
    : buildStandardRegions(safeWidth, safeHeight)
}

export function countsTowardConflictVerification(region: ScanRegion): boolean {
  return region.kind !== 'resampled'
}

function buildStandardRegions(width: number, height: number): ScanRegion[] {
  const regions: ScanRegion[] = [whole(width, height, 0, 1600, 'overview')]

  // The worker skips this pass for normal/high-contrast photos. It gives faded
  // labels one inexpensive second chance without doubling every empty scan.
  regions.push(whole(width, height, 0, 1600, 'overview', 'contrast-if-low'))

  // Large QR codes can be cut by every small sliding window. Two overlapping
  // halves keep large codes intact and separate codes on opposite sides.
  if (Math.max(width, height) > 1600) {
    regions.push(...gridRegions(width, height, 2, 0.12, 2800, 'coarse'))
  }

  regions.push(...slidingSquareRegions(
    width,
    height,
    clamp(Math.round(Math.min(width, height) / 4), 720, 1200),
    STANDARD_DETAIL_OVERLAP,
    'detail',
  ))

  // Expensive whole-image and rotation passes come last so small label QR codes
  // get native-detail windows before the worker budget is consumed.
  regions.push(whole(width, height, 0, 3200, 'fallback'))
  regions.push(whole(width, height, 90, 2200, 'fallback'))
  regions.push(whole(width, height, 180, 2200, 'fallback'))
  regions.push(whole(width, height, 270, 2200, 'fallback'))
  return regions
}

function buildDeepRegions(width: number, height: number): ScanRegion[] {
  const regions: ScanRegion[] = []

  // Deep scan supplements the standard pass. Start at a distinct scale, then
  // try pixel treatments for faded and unevenly lit labels.
  regions.push(whole(width, height, 0, 2400, 'overview'))
  regions.push(whole(width, height, 0, 2400, 'overview', 'contrast'))
  regions.push(whole(width, height, 0, 2400, 'overview', 'local-threshold'))

  // A low-resolution copy of the complete 3x3 coverage recovers soft QR module
  // edges before the more expensive high-resolution regions consume the budget.
  const resampled = gridRegions(width, height, 3, 0.16, 600, 'resampled')

  // Preserve complete higher-resolution coverage before enhanced copies, so a
  // small code or one difficult area cannot be weakened by the resampled pass.
  const coarse = gridRegions(width, height, 3, 0.16, 2200, 'coarse')
  const fine = slidingSquareRegions(
    width,
    height,
    clamp(Math.round(Math.min(width, height) / 5), 480, 900),
    DEEP_FINE_OVERLAP,
    'fine',
  )
  regions.push(...resampled, ...coarse, ...fine)
  regions.push(...withTreatment(coarse, 'contrast'))
  regions.push(...withTreatment(fine, 'contrast'))
  regions.push(...withTreatment(fine, 'local-threshold'))

  // Rotation changes the sampling grid and can recover finder patterns damaged
  // by interpolation. Keep it last and bounded to one full-image scale.
  regions.push(whole(width, height, 90, 2400, 'fallback', 'contrast'))
  regions.push(whole(width, height, 180, 2400, 'fallback', 'contrast'))
  regions.push(whole(width, height, 270, 2400, 'fallback', 'contrast'))
  return regions
}

function slidingSquareRegions(
  width: number,
  height: number,
  tile: number,
  overlap: number,
  kind: ScanRegion['kind'],
): ScanRegion[] {
  const safeTile = Math.min(width, height, tile)
  const step = Math.max(1, Math.round(safeTile * (1 - overlap)))
  const xPositions = axisPositions(width, safeTile, step)
  const yPositions = axisPositions(height, safeTile, step)
  const regions: ScanRegion[] = []

  // Row-major order checks the label area at the top first while still
  // covering the complete image for atypical layouts.
  for (const sy of yPositions) {
    for (const sx of xPositions) {
      regions.push({
        sx,
        sy,
        sw: safeTile,
        sh: safeTile,
        rotation: 0,
        maxEdge: safeTile,
        kind,
        treatment: 'original',
      })
    }
  }
  return regions
}

function axisPositions(length: number, tile: number, step: number): number[] {
  if (tile >= length) return [0]
  const last = length - tile
  const positions: number[] = []
  for (let position = 0; position < last; position += step) positions.push(position)
  if (positions.at(-1) !== last) positions.push(last)
  return positions
}

function whole(
  width: number,
  height: number,
  rotation: ScanRegion['rotation'],
  maxEdge: number,
  kind: ScanRegion['kind'],
  treatment: PixelTreatment = 'original',
): ScanRegion {
  return { sx: 0, sy: 0, sw: width, sh: height, rotation, maxEdge, kind, treatment }
}

function gridRegions(
  width: number,
  height: number,
  divisions: number,
  overlap: number,
  maxEdge: number,
  kind: ScanRegion['kind'],
): ScanRegion[] {
  const cellWidth = width / divisions
  const cellHeight = height / divisions
  const regions: ScanRegion[] = []
  for (let row = 0; row < divisions; row += 1) {
    for (let column = 0; column < divisions; column += 1) {
      const sx = Math.max(0, column * cellWidth - cellWidth * overlap)
      const sy = Math.max(0, row * cellHeight - cellHeight * overlap)
      const right = Math.min(width, (column + 1) * cellWidth + cellWidth * overlap)
      const bottom = Math.min(height, (row + 1) * cellHeight + cellHeight * overlap)
      regions.push({
        sx,
        sy,
        sw: right - sx,
        sh: bottom - sy,
        rotation: 0,
        maxEdge,
        kind,
        treatment: 'original',
      })
    }
  }
  return regions
}

function withTreatment(regions: ScanRegion[], treatment: PixelTreatment): ScanRegion[] {
  return regions.map(region => ({ ...region, treatment }))
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(maximum, Math.max(minimum, value))
}
