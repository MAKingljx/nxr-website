import assert from 'node:assert/strict'
import test from 'node:test'
import {
  createRegionPixelProvider,
  type RegionGeometry,
  type RenderedRegionPixels,
} from '../src/lib/region-pixel-provider.ts'

const baseGeometry: RegionGeometry = {
  sx: 10,
  sy: 20,
  sw: 30,
  sh: 40,
  rotation: 0,
  maxEdge: 900,
}

test('same geometry reuses one render and returns independent masking buffers', () => {
  let renders = 0
  let releases = 0
  const provider = createRegionPixelProvider(() => {
    renders += 1
    return rendered([10, 20, 30, 255], () => { releases += 1 })
  })

  const first = provider.pixelsFor(baseGeometry)
  first.data.fill(255)
  const second = provider.pixelsFor({ ...baseGeometry })

  assert.equal(renders, 1)
  assert.deepEqual([...second.data], [10, 20, 30, 255])
  assert.notEqual(first.data, second.data)
  provider.release()
  assert.equal(releases, 1)
})

test('geometry, rotation and max edge changes release the previous render immediately', () => {
  const released: number[] = []
  let renderId = 0
  const provider = createRegionPixelProvider(() => {
    const id = ++renderId
    return rendered([id, id, id, 255], () => { released.push(id) })
  })

  provider.pixelsFor(baseGeometry)
  provider.pixelsFor({ ...baseGeometry, maxEdge: 600 })
  assert.deepEqual(released, [1])
  provider.pixelsFor({ ...baseGeometry, maxEdge: 600, rotation: 90 })
  assert.deepEqual(released, [1, 2])
  provider.pixelsFor({ ...baseGeometry, maxEdge: 600, rotation: 90, sx: 11 })
  assert.deepEqual(released, [1, 2, 3])
  provider.release()
  assert.deepEqual(released, [1, 2, 3, 4])
})

test('render validation failure releases both the prior and rejected resources', () => {
  const released: string[] = []
  const provider = createRegionPixelProvider(region => region.sx === baseGeometry.sx
    ? { ...rendered([1, 2, 3, 255], () => { released.push('prior') }) }
    : {
        data: new Uint8ClampedArray(3),
        width: 1,
        height: 1,
        release: () => { released.push('rejected') },
      })

  provider.pixelsFor(baseGeometry)
  assert.throws(() => provider.pixelsFor({ ...baseGeometry, sx: 99 }), /Invalid rendered region dimensions/)
  assert.deepEqual(released, ['prior', 'rejected'])
  provider.release()
  assert.deepEqual(released, ['prior', 'rejected'])
})

test('caller finally releases a cached render after scan failure', () => {
  let releases = 0
  const provider = createRegionPixelProvider(() => rendered(
    [1, 2, 3, 255],
    () => { releases += 1 },
  ))

  assert.throws(() => {
    try {
      provider.pixelsFor(baseGeometry)
      throw new Error('decode failed')
    } finally {
      provider.release()
    }
  }, /decode failed/)
  assert.equal(releases, 1)
})

function rendered(values: number[], release: () => void): RenderedRegionPixels {
  return { data: new Uint8ClampedArray(values), width: 1, height: 1, release }
}
