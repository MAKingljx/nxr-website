import assert from 'node:assert/strict'
import test from 'node:test'
import vm from 'node:vm'
import { readFileSync } from 'node:fs'
import { computed, effectScope, reactive, ref, watch, nextTick } from 'vue'
import { parse } from '@vue/compiler-sfc'
import literalZhCN from '../src/i18n/literal-zh-CN.js'

const source = readFileSync(new URL('../src/views/nxr/orders/components/OrderFinanceExceptionPanel.vue', import.meta.url), 'utf8')
const script = parse(source).descriptor.scriptSetup.content.replace(/^import .*\n/gm, '')
const defaultContext = (orderId = 1, extra = {}) => ({orderId,statusCode:'payment_exception',resumeStatusCode:'grading',amount:32.5,currencyCode:'USD',pendingExceptionIds:[4,5],canRestore:true,canCancel:true,hasReceived:false,restoreBlockReason:null,cancelBlockReason:null,...extra})
const deferred = () => {let resolve,reject;const promise = new Promise((yes,no) => {resolve=yes;reject=no});return {promise,resolve,reject}}
const flush = async () => {await nextTick();await Promise.resolve();await Promise.resolve()}
const clone = value => JSON.parse(JSON.stringify(value))

function fixture(options = {}) {
  const scope = effectScope(), props = reactive({orderId:1,active:options.active ?? true,refreshKey:0}), posts = [], reads = [], events = []
  let cleanup
  const context = {computed,reactive,ref,watch,Intl,defineProps:() => props,defineEmits:() => (...event) => events.push(event),onBeforeUnmount:fn => {cleanup = fn},activeLocale:() => 'en',tx:(value,parameters={}) => Object.entries(parameters).reduce((message,[key,text]) => message.replaceAll(`{${key}}`,String(text)),value),localizeBackendMessage:value => value,formatCustomerDate:value => value || '-',getOrderFinanceExceptions:orderId => {reads.push(['list',orderId]);return options.list ? options.list(orderId) : Promise.resolve({data:[{id:4,orderId,resolutionStatusCode:'open'}]})},getOrderFinanceReviewContext:orderId => {reads.push(['context',orderId]);return options.context ? options.context(orderId) : Promise.resolve({data:defaultContext(orderId)})},reviewOrderFinanceExceptions:(orderId,payload) => {posts.push({orderId,payload:clone(payload)});return options.post ? options.post(orderId,payload) : Promise.resolve({data:defaultContext(orderId,{statusCode:payload.action === 'cancel' ? 'cancelled' : payload.action === 'restore' ? 'grading' : 'payment_exception',pendingExceptionIds:payload.action === 'manual_review' ? [4,5] : [],canRestore:false,canCancel:false})})}}
  scope.run(() => vm.runInNewContext(script + '\nglobalThis.state = {rows,reviewContext,loading,error,dialogOpen,dialogContext,reviewAction,reviewForm,saving,submitError,canSubmit,dialogBlockReason};',context))
  return {props,posts,reads,events,context,state:context.state,stop:() => {cleanup();scope.stop()}}
}
function fillReview(f,confirmed=true) {Object.assign(f.state.reviewForm,{evidenceReference:'  BANK-REF-20261003  ',reason:'  Full payment verified against bank records.  ',confirmed})}

test('inactive finance panels do not fetch; activation loads both list and authoritative review context',async() => {
  const f = fixture({active:false})
  try {await flush();assert.equal(f.reads.length,0);f.props.active=true;await flush();assert.deepEqual(f.reads,[['list',1],['context',1]]);assert.equal(f.state.reviewContext.value.resumeStatusCode,'grading');assert.equal(f.state.rows.value[0].id,4)}finally{f.stop()}
})

test('newer orders and refreshes discard earlier review responses',async() => {
  const old=deferred(),current=deferred(),f=fixture({context:id => id === 1 ? old.promise : current.promise})
  try {f.props.orderId=2;current.resolve({data:defaultContext(2)});await flush();assert.equal(f.state.reviewContext.value.orderId,2);old.resolve({data:defaultContext(1)});await flush();assert.equal(f.state.reviewContext.value.orderId,2);assert.equal(f.state.rows.value[0].orderId,2)}finally{f.stop()}
})

test('blocked restore and cancel never open a dialog or submit, while manual review remains available',async() => {
  const f=fixture({context:async id => ({data:defaultContext(id,{canRestore:false,canCancel:false,restoreBlockReason:'The previous order progress could not be verified',cancelBlockReason:'Cards have already been received; keep the order paused for manual review'})})})
  try {await flush();f.context.openReview('restore');assert.equal(f.state.dialogOpen.value,false);f.context.openReview('cancel');assert.equal(f.state.dialogOpen.value,false);f.context.openReview('manual_review');fillReview(f,false);assert.equal(f.state.canSubmit.value,true);await f.context.submitReview();assert.deepEqual(f.posts[0].payload,{action:'manual_review',exceptionIds:[4,5],evidenceReference:'BANK-REF-20261003',reason:'Full payment verified against bank records.'});assert.equal(f.state.reviewContext.value.statusCode,'payment_exception');assert.equal(f.events[0][0],'reviewed')}finally{f.stop()}
})

test('restore requires full payment confirmation and uses context amount, currency and exception ids without a chosen target',async() => {
  const pending=deferred(),f=fixture({post:() => pending.promise})
  try {await flush();f.context.openReview('restore');fillReview(f,false);await f.context.submitReview();assert.equal(f.posts.length,0);f.state.reviewForm.confirmed=true;const saving=f.context.submitReview();await f.context.submitReview();assert.equal(f.posts.length,1);assert.deepEqual(f.posts[0],{orderId:1,payload:{action:'restore',exceptionIds:[4,5],evidenceReference:'BANK-REF-20261003',reason:'Full payment verified against bank records.',amount:32.5,currencyCode:'USD'}});assert.equal(f.state.saving.value,true);pending.resolve({data:defaultContext(1,{statusCode:'grading',pendingExceptionIds:[],canRestore:false,canCancel:false})});await saving;assert.equal(f.state.dialogOpen.value,false);assert.equal(f.state.saving.value,false);assert.equal(f.state.reviewContext.value.statusCode,'grading');assert.deepEqual(f.events,[['reviewed',1]])}finally{f.stop()}
})

test('confirmed refund cancellation carries evidence and reason without calling a refund or transfer API',async() => {
  const f=fixture()
  try {await flush();f.context.openReview('cancel');fillReview(f,false);await f.context.submitReview();assert.equal(f.posts.length,0);f.state.reviewForm.confirmed=true;await f.context.submitReview();assert.deepEqual(f.posts[0].payload,{action:'cancel',exceptionIds:[4,5],evidenceReference:'BANK-REF-20261003',reason:'Full payment verified against bank records.'});assert.equal(f.state.reviewContext.value.statusCode,'cancelled');assert.equal(f.state.dialogOpen.value,false)}finally{f.stop()}
})

test('failed submission keeps the dialog, entered evidence and reason for an explicit retry',async() => {
  let fail=true
  const f=fixture({post:async id => {if(fail)throw {response:{data:{message:'The payment exceptions changed; refresh and review again'}}};return {data:defaultContext(id,{statusCode:'grading',pendingExceptionIds:[]})}}})
  try {await flush();f.context.openReview('restore');fillReview(f);await f.context.submitReview();assert.equal(f.state.dialogOpen.value,true);assert.equal(f.state.saving.value,false);assert.equal(f.state.reviewForm.evidenceReference,'  BANK-REF-20261003  ');assert.equal(f.state.reviewForm.reason,'  Full payment verified against bank records.  ');assert.match(f.state.submitError.value,/payment exceptions changed/);assert.equal(f.events.length,0);fail=false;await f.context.submitReview();assert.equal(f.posts.length,2);assert.equal(f.state.dialogOpen.value,false)}finally{f.stop()}
})

test('refreshing changed context preserves text, resets confirmation and honors new backend blocks',async() => {
  let block=false
  const f=fixture({context:async id => ({data:defaultContext(id,{canRestore:!block,restoreBlockReason:block ? 'An active checkout requires manual review' : null})})})
  try {await flush();f.context.openReview('restore');fillReview(f);block=true;f.props.refreshKey+=1;await flush();assert.equal(f.state.canSubmit.value,false);assert.match(f.state.dialogBlockReason.value,/details changed/);await f.context.refreshReview();assert.equal(f.state.reviewForm.confirmed,false);assert.equal(f.state.reviewForm.evidenceReference,'  BANK-REF-20261003  ');assert.equal(f.state.dialogBlockReason.value,'An active checkout requires manual review');await f.context.submitReview();assert.equal(f.posts.length,0)}finally{f.stop()}
})

test('changing orders during a submission suppresses old UI updates and success events',async() => {
  const pending=deferred(),f=fixture({post:() => pending.promise})
  try {await flush();f.context.openReview('restore');fillReview(f);const save=f.context.submitReview();f.props.orderId=2;await flush();assert.equal(f.state.dialogOpen.value,false);assert.equal(f.state.reviewContext.value.orderId,2);pending.resolve({data:defaultContext(1,{statusCode:'grading',pendingExceptionIds:[]})});await save;assert.equal(f.state.reviewContext.value.orderId,2);assert.equal(f.events.length,0);assert.equal(f.state.saving.value,false)}finally{f.stop()}
})

test('closing or unmounting the panel discards pending reads and review responses',async() => {
  const pending=deferred(),f=fixture({post:() => pending.promise})
  await flush();f.context.openReview('manual_review');fillReview(f,false);const save=f.context.submitReview();f.props.active=false;f.stop();pending.resolve({data:defaultContext(1)});await save;assert.equal(f.events.length,0);assert.equal(f.state.dialogOpen.value,false);assert.equal(f.state.reviewContext.value,null)
})

test('a successful review invalidates an older concurrent refresh before it can restore paused state',async() => {
  const refresh=deferred(),post=deferred();let reads=0
  const f=fixture({context:id => ++reads === 1 ? Promise.resolve({data:defaultContext(id)}) : refresh.promise,post:() => post.promise})
  try {await flush();f.context.openReview('restore');fillReview(f);const save=f.context.submitReview();f.props.refreshKey+=1;post.resolve({data:defaultContext(1,{statusCode:'grading',pendingExceptionIds:[]})});await save;refresh.resolve({data:defaultContext(1)});await flush();assert.equal(f.state.reviewContext.value.statusCode,'grading');assert.deepEqual(f.events,[['reviewed',1]])}finally{f.stop()}
})

test('blank or overlong references and reasons cannot submit',async() => {
  const f=fixture()
  try {await flush();f.context.openReview('manual_review');fillReview(f,false);f.state.reviewForm.evidenceReference=' ';await f.context.submitReview();f.state.reviewForm.evidenceReference='x'.repeat(256);await f.context.submitReview();f.state.reviewForm.evidenceReference='REF';f.state.reviewForm.reason='x'.repeat(1001);await f.context.submitReview();assert.equal(f.posts.length,0)}finally{f.stop()}
})

test('finance API wrappers use only the review endpoint and keep errors available to the dialog',async() => {
  const apiSource=readFileSync(new URL('../src/api/nxr/orders.js',import.meta.url),'utf8').replace(/^import .*\n/gm,'').replaceAll('export ','')
  const calls=[],context={request:options => {calls.push(options);return Promise.resolve({data:{}})}}
  vm.runInNewContext(apiSource,context)
  await context.getOrderFinanceReviewContext(7);await context.reviewOrderFinanceExceptions(7,{action:'restore'})
  assert.equal(calls[0].url,'/api/admin/orders/7/finance-exceptions/review-context');assert.equal(calls[0].method,'get');assert.equal(calls[1].url,'/api/admin/orders/7/finance-exceptions/review');assert.equal(calls[1].method,'post');assert.equal(calls[1].suppressErrorMessage,true);assert.equal(calls[1].headers.repeatSubmit,false)
})

test('all review UI text and supplied backend blockers have Chinese translations',() => {
  const userText=[...source.matchAll(/\$tx\('([^']+)'/g),...source.matchAll(/tx\('([^']+)'/g)].map(match => match[1])
  const blockers=['This payment exception requires manual review','This order is not paused for financial review','The payment exceptions changed; refresh and review again','The previous order progress could not be verified','Cards have already been received; keep the order paused for manual review','The master parcel has already shipped; keep the order paused for manual review','An active checkout requires manual review','Other confirmed payments require manual review','Verified payment amount must equal the full order total','Verified payment currency must match the order','A new payment reference is required; this reference was already recorded','The order custody has changed; keep the order paused for manual review']
  for(const text of [...new Set([...userText,...blockers])])assert.ok(literalZhCN[text],`Missing Chinese translation: ${text}`)
})

test('an empty pending set cannot submit any finance review even when the order remains paused',async() => {
  const f=fixture({context:async id => ({data:defaultContext(id,{pendingExceptionIds:[],canRestore:false,canCancel:false})})})
  try {await flush();for(const action of ['restore','cancel','manual_review'])f.context.openReview(action);assert.equal(f.state.dialogOpen.value,false);assert.equal(f.posts.length,0)}finally{f.stop()}
})

test('a view reopened while its review commits refreshes from the server instead of applying the old scope response',async() => {
  const pending=deferred();let committed=false
  const f=fixture({context:async id => ({data:defaultContext(id,committed ? {statusCode:'grading',pendingExceptionIds:[],canRestore:false,canCancel:false} : {})}),post:() => pending.promise})
  try {await flush();f.context.openReview('restore');fillReview(f);const save=f.context.submitReview();f.props.active=false;f.props.active=true;await flush();committed=true;pending.resolve({data:defaultContext(1,{statusCode:'grading',pendingExceptionIds:[]})});await save;assert.equal(f.state.reviewContext.value.statusCode,'grading');assert.equal(f.state.dialogOpen.value,false);assert.deepEqual(f.events,[['reviewed',1]]);assert.ok(f.reads.filter(call => call[0]==='context').length>=3)}finally{f.stop()}
})
