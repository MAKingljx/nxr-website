import assert from 'node:assert/strict'
import test from 'node:test'
import vm from 'node:vm'
import { readFileSync } from 'node:fs'
import { orderDetailSection } from '../src/views/nxr/orders/orderPresentation.js'

const source = readFileSync(new URL('../src/views/nxr/orders/index.vue', import.meta.url),'utf8')
const detailFunction = source.slice(source.indexOf('async function openDetail('), source.indexOf('function refreshDetail('))
const refreshFunction = source.slice(source.indexOf('async function refreshFinanceReviewed('), source.indexOf('async function openMasterBatch('))
const deferred = () => {let resolve;const promise = new Promise(yes => {resolve=yes});return {promise,resolve}}
function fixture() {
  const response=deferred(), calls=[]
  const context={detailGeneration:0,detail:{value:{id:1,statusCode:'payment_exception'}},detailOpen:{value:true},detailTab:{value:'finance'},detailRefresh:{value:0},operations:{value:null},statusForm:{},intakeForm:{},trackingForm:{},taskDrafts:{},orderDetailSection,getGradingOrder:id => {calls.push(['detail',id]);return response.promise},getOrderOperations:async id => {calls.push(['operations',id]);return {data:{intakeCode:'TEST',expectedCardCount:1,workTasks:[]}}},loadOrders:async() => {calls.push(['list'])},proxy:{$modal:{msgSuccess:message => calls.push(['success',message])}},tx:text => text}
  vm.runInNewContext(detailFunction+refreshFunction,context)
  return {context,response,calls,finish:() => response.resolve({data:{id:1,statusCode:'grading',shipments:[]}})}
}

test('finance success refreshes both order detail and list while preserving the latest selected section',async() => {
  const f=fixture(),pending=f.context.refreshFinanceReviewed(1)
  f.context.detailTab.value='support'
  f.finish();await pending
  assert.equal(f.context.detail.value.statusCode,'grading');assert.equal(f.context.detailTab.value,'support');assert.equal(f.context.detailRefresh.value,1);assert.ok(f.calls.some(call => call[0] === 'list'));assert.ok(f.calls.some(call => call[0] === 'operations'))
})

test('finance refresh cannot reopen a closed drawer or overwrite a newly selected order',async() => {
  for(const change of ['closed','other-order']){
    const f=fixture(),pending=f.context.refreshFinanceReviewed(1)
    if(change==='closed')f.context.detailOpen.value=false
    else f.context.detail.value={id:2,statusCode:'received'}
    f.finish();await pending
    assert.equal(f.context.detailRefresh.value,0)
    if(change==='closed')assert.equal(f.context.detailOpen.value,false)
    else assert.equal(f.context.detail.value.id,2)
  }
})

test('an event for an order no longer open performs no parent refresh',async() => {
  const f=fixture();await f.context.refreshFinanceReviewed(2);assert.equal(f.calls.length,0)
})
