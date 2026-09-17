import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { transform } from 'esbuild'

const source = await readFile(new URL('../src/views/nxr/agent-workbench/lib/agentWorkbench.ts',import.meta.url),'utf8')
const formatters = source.slice(source.indexOf('export function formatPoints('),source.indexOf('export function privateTrackingUrl('))
const compiled = await transform(`let locale='en'; const activeLocale=()=>locale; export const setLocale=value=>{locale=value};\n${formatters}`,{loader:'ts',format:'esm',target:'es2022'})
const {formatMoney,setLocale} = await import(`data:text/javascript;base64,${Buffer.from(compiled.code).toString('base64')}`)

test('admin exact USD and CNY preserve cents at the DECIMAL(18,2) limit',()=>{
  for(const locale of ['en','zh-CN']){
    setLocale(locale)
    for(const currency of ['USD','CNY']){
      assert.match(formatMoney('9999999999999999.99',currency),/9,999,999,999,999,999\.99/)
      assert.match(formatMoney('-9999999999999999.98',currency),/-.*9,999,999,999,999,999\.98/)
    }
  }
})
test('admin exact JPY rounds without a fractional suffix or Number conversion',()=>{
  setLocale('en')
  assert.match(formatMoney('9999999999999999.00','JPY'),/9,999,999,999,999,999$/)
  assert.match(formatMoney('9999999999999999.50','JPY'),/10,000,000,000,000,000$/)
  assert.match(formatMoney('12.49','JPY'),/12$/)
})
test('normal numbers retain Intl behavior and exact decimal strings keep signs and carry',()=>{
  setLocale('en')
  for(const currency of ['USD','CNY','JPY']) for(const value of [0,12.5,-0.01,1234.567]){
    const expected=new Intl.NumberFormat('en',{style:'currency',currency}).format(value)
    assert.equal(formatMoney(value,currency),expected)
    assert.equal(formatMoney(String(value),currency),expected)
  }
  assert.equal(formatMoney('-0.01','USD'),'-$0.01')
  assert.equal(formatMoney('999.995','USD'),'$1,000.00')
})
