import assert from 'node:assert/strict'
import test from 'node:test'
import { mapConcurrent } from '../src/lib/batch-processing.ts'
test('bounded loader preserves natural order when later files finish first', async () => {
  let active = 0, peak = 0
  const values = await mapConcurrent([0,1,2,3,4,5], 3, async index => {
    active++; peak = Math.max(peak, active)
    await new Promise(resolve => setTimeout(resolve, 8 - index))
    active--; return index * 2
  })
  assert.equal(peak, 3)
  assert.deepEqual(values, [0,2,4,6,8,10])
})
test('loader drains running work and stops scheduling after a failure', async () => {
  let active = 0, started = 0
  await assert.rejects(mapConcurrent([0,1,2,3], 2, async index => {
    started++; active++
    try { if (index === 0) throw new Error('unreadable'); await new Promise(resolve => setTimeout(resolve, 2)); return index }
    finally { active-- }
  }), /unreadable/)
  assert.equal(active, 0); assert.equal(started, 2)
})
