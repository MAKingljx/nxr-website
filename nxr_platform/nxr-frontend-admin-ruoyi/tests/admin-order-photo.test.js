import assert from 'node:assert/strict'
import test from 'node:test'
import vm from 'node:vm'
import { readFileSync } from 'node:fs'
import { reactive, ref, watch, nextTick } from 'vue'
import { parse } from '@vue/compiler-sfc'

const component = readFileSync(new URL('../src/views/nxr/orders/components/OrderApplicationPhoto.vue', import.meta.url), 'utf8')
const script = parse(component).descriptor.scriptSetup.content.replace(/^import .*\n/gm, '')
const flush = async () => { await nextTick(); await Promise.resolve() }

function mountPhoto() {
  const props = reactive({photoId:5,orderId:1,label:'Front'}), calls = [], pending = [], revoked = []
  let cleanup, created = 0
  const context = {ref,watch,defineProps:() => props,onBeforeUnmount:callback => {cleanup = callback},tx:value => `translated:${value}`,localizeBackendMessage:value => `localized:${value}`,URL:{createObjectURL:()=>`blob:${++created}`,revokeObjectURL:value => revoked.push(value)},request:config => {calls.push(config);return new Promise(resolve => pending.push(resolve))}}
  vm.runInNewContext(script + '\nglobalThis.photoUrl = url; globalThis.photoError = error;', context)
  return {props,calls,pending,revoked,context,unmount:() => cleanup()}
}

test('photo preview passes the current order scope and reloads when a reused photo moves orders', async () => {
  const photo = mountPhoto()
  assert.equal(photo.calls[0].url, '/api/admin/order-photos/5')
  assert.equal(photo.calls[0].params.orderId, 1)
  photo.pending[0]({type:'image/png'})
  await flush()
  assert.equal(photo.context.photoUrl.value, 'blob:1')
  photo.props.orderId = 2
  await flush()
  assert.equal(photo.calls[1].params.orderId, 2)
  assert.deepEqual(photo.revoked, ['blob:1'])
  assert.equal(photo.context.photoUrl.value, '')
  photo.unmount()
})

test('stale photo responses cannot replace the current order preview or survive unmount', async () => {
  const photo = mountPhoto()
  photo.props.orderId = 2
  await flush()
  photo.pending[0]({type:'image/png'})
  await flush()
  assert.equal(photo.context.photoUrl.value, '')
  photo.unmount()
  photo.pending[1]({type:'image/png'})
  await flush()
  assert.equal(photo.context.photoUrl.value, '')
})

test('non-image access failures use localized user-facing copy', async () => {
  const photo = mountPhoto()
  photo.pending[0]({type:'application/json'})
  await flush()
  assert.match(photo.context.photoError.value, /^localized:translated:Unable to load/)
  assert.equal(photo.context.photoUrl.value, '')
  photo.unmount()
})
