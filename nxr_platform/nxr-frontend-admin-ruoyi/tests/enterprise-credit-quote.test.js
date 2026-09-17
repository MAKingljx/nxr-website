import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import { transform } from 'esbuild'
import { effectScope, reactive } from 'vue'
const require = createRequire(import.meta.url)
const source = (await readFile(new URL('../src/composables/useEnterpriseCreditQuote.ts', import.meta.url), 'utf8'))
  .replace("from 'vue'", `from '${pathToFileURL(require.resolve('vue')).href}'`)
  .replace("import { tx } from '@/i18n'", 'const tx = value => value')
const compiled = await transform(source, { loader:'ts', format:'esm', target:'es2022' })
const { useEnterpriseCreditQuote, sameCreditAmount } = await import(`data:text/javascript;base64,${Buffer.from(compiled.code).toString('base64')}`)
const quote = (currency,amount,version=1) => ({sourceCurrency:currency,sourceAmount:String(amount),settingsVersion:version,cnyPerUnit:'1',pointsPerCny:'1',points:String(amount),unitCode:'PTS'})
function fixture(fetcher){const scope=effectScope(),input=reactive({currencyCode:'CNY',amount:'',enabled:true});const result=scope.run(()=>useEnterpriseCreditQuote(()=>({...input}),fetcher));return {scope,input,result}}
function deferred(){let resolve;const promise=new Promise(yes=>{resolve=yes});return {promise,resolve}}
test('amount or currency edits immediately invalidate the visible quote',async()=>{
  const {scope,input,result}=fixture(async value=>quote(value.currencyCode,value.amount))
  try {input.amount='10';await result.refresh();assert.equal(result.valid.value,true);assert.equal(result.quote.value.points,'10');input.amount='20';assert.equal(result.valid.value,false);assert.equal(result.quote.value,null);await result.refresh();assert.equal(result.valid.value,true);input.currencyCode='USD';assert.equal(result.valid.value,false);assert.equal(result.quote.value,null)}finally{scope.stop()}
})
test('late responses cannot overwrite a newer quote',async()=>{
  const first=deferred(),second=deferred();let calls=0;const {scope,input,result}=fixture(()=>++calls===1?first.promise:second.promise)
  try {input.amount='10';const old=result.refresh();input.amount='20';const current=result.refresh();second.resolve(quote('CNY',20,2));await current;assert.equal(result.quote.value.settingsVersion,2);first.resolve(quote('CNY',10,1));await old;assert.equal(result.quote.value.points,'20');assert.equal(result.valid.value,true)}finally{scope.stop()}
})
test('missing rates leave payment disabled until an explicit refresh succeeds',async()=>{
  let reject=true;const {scope,input,result}=fixture(async value=>{if(reject)throw new Error('Rate unavailable');return quote(value.currencyCode,value.amount,2)})
  try {input.amount='10';await result.refresh();assert.equal(result.quote.value,null);assert.equal(result.valid.value,false);assert.match(result.error.value,/Rate unavailable/);reject=false;assert.equal(result.valid.value,false);await result.refresh();assert.equal(result.valid.value,true);assert.equal(result.quote.value.settingsVersion,2);result.invalidate();assert.equal(result.valid.value,false)}finally{scope.stop()}
})
test('closed scopes discard pending quotes and disabled forms never quote',async()=>{
  const pending=deferred();let calls=0;const {scope,input,result}=fixture(()=>{calls++;return pending.promise});input.enabled=false;input.amount='10';await result.refresh();assert.equal(calls,0);input.enabled=true;const loading=result.refresh();scope.stop();pending.resolve(quote('CNY',10));await loading;assert.equal(result.quote.value,null);assert.equal(result.valid.value,false)
})

test('quote matching preserves cents beyond floating point precision',()=>{
  assert.equal(sameCreditAmount('99999999999999.99','99999999999999.98'),false)
  assert.equal(sameCreditAmount('001.20','1.200'),true)
  assert.equal(sameCreditAmount('',''),false)
})
