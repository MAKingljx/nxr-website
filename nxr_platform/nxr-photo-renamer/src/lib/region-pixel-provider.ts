import type { ScanRegion } from './scan-regions'

export type RegionGeometry = Pick<ScanRegion, 'sx' | 'sy' | 'sw' | 'sh' | 'rotation' | 'maxEdge'>

export interface RegionPixels {
  data: Uint8ClampedArray
  width: number
  height: number
}

export interface RenderedRegionPixels extends RegionPixels {
  release: () => void
}

export interface RegionPixelProvider {
  pixelsFor: (region: RegionGeometry) => RegionPixels
  release: () => void
}

/**
 * Keeps only the current region's immutable render. Each consumer receives a
 * fresh buffer because jsQR masking mutates its input between decoded codes.
 */
export function createRegionPixelProvider(
  render: (region: RegionGeometry) => RenderedRegionPixels,
): RegionPixelProvider {
  let current: { geometry: RegionGeometry, pixels: RenderedRegionPixels } | null = null

  const release = () => {
    const previous = current
    current = null
    previous?.pixels.release()
  }

  return {
    pixelsFor(region) {
      if (!current || !sameGeometry(current.geometry, region)) {
        release()
        const rendered = render(region)
        if (!validDimensions(rendered)) {
          rendered.release()
          throw new Error('Invalid rendered region dimensions.')
        }
        current = { geometry: copyGeometry(region), pixels: rendered }
      }

      return {
        data: current.pixels.data.slice(),
        width: current.pixels.width,
        height: current.pixels.height,
      }
    },
    release,
  }
}

function sameGeometry(left: RegionGeometry, right: RegionGeometry): boolean {
  return left.sx === right.sx
    && left.sy === right.sy
    && left.sw === right.sw
    && left.sh === right.sh
    && left.rotation === right.rotation
    && left.maxEdge === right.maxEdge
}

function copyGeometry(region: RegionGeometry): RegionGeometry {
  const { sx, sy, sw, sh, rotation, maxEdge } = region
  return { sx, sy, sw, sh, rotation, maxEdge }
}

function validDimensions(pixels: RegionPixels): boolean {
  return Number.isInteger(pixels.width)
    && Number.isInteger(pixels.height)
    && pixels.width > 0
    && pixels.height > 0
    && pixels.data.length === pixels.width * pixels.height * 4
}
