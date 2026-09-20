import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
import {readFile,writeFile,readdir,mkdtemp,rm,mkdir,realpath} from 'node:fs/promises';
import {createHash} from 'node:crypto';import os from 'node:os';import path from 'node:path';
import {installOpfsPicker,directoryFiles} from '../tests/fixtures.ts';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const require=createRequire(root+'/package.json'),{_electron,expect}=require('@playwright/test');
if (!process.env.NXR_CORPUS_DIR) throw new Error('Set NXR_CORPUS_DIR to the private regression corpus directory.');
const work=await realpath(process.env.NXR_CORPUS_DIR);
const corpus=JSON.parse(await readFile(path.join(work,'manifest.json'),'utf8'));
assert.equal(corpus.schemaVersion,1);assert.ok(Array.isArray(corpus.fixtures) && corpus.fixtures.length > 0);
const manifest=[];
for(const fixture of corpus.fixtures){
 assert.match(fixture.batch,/^[a-zA-Z0-9_-]{1,64}$/);
 assert.equal(typeof fixture.file,'string');assert.ok(!path.isAbsolute(fixture.file));
 const input=await realpath(path.resolve(work,fixture.file));assert.ok(input.startsWith(work+path.sep));
 assert.equal(path.basename(input),fixture.name);
 assert.match(input,/\.(jpe?g|png|webp)$/i);assert.match(fixture.sha256,/^[a-f0-9]{64}$/);
 assert.equal(createHash('sha256').update(await readFile(input)).digest('hex'),fixture.sha256);
 manifest.push({...fixture,path:input});
}
const expected=corpus.pairs;
assert.ok(Array.isArray(expected) && expected.length > 0);
const output=path.resolve(process.env.NXR_CORPUS_REPORT_DIR || path.join(root,'test-results/corpus'));
await mkdir(output,{recursive:true});
const profile=await mkdtemp(path.join(os.tmpdir(),'nxr-gold-ui-'));let app;
const report={corpus:corpus.name,version:JSON.parse(await readFile(root+'/package.json','utf8')).version,batches:[],errors:[],remoteRequests:[],originalsUnchanged:false};
try{
 app=await _electron.launch({cwd:root,args:['.',`--user-data-dir=${profile}`],timeout:60000});
 const page=await app.firstWindow();await page.waitForURL('nxr://app/');
 await app.evaluate(({BrowserWindow})=>{for(const win of BrowserWindow.getAllWindows()){win.hide();win.on('show',()=>win.hide());}});
 await page.evaluate(()=>{window.__peakWorkers=0;setInterval(()=>{window.__peakWorkers=Math.max(window.__peakWorkers,Number(document.querySelector('[data-testid="active-worker-count"]')?.textContent)||0);},250);});
 await page.addInitScript(()=>{
  const Original=window.Worker;window.__qrMessages=0;window.__peakWorkers=0;
  setInterval(()=>{window.__peakWorkers=Math.max(window.__peakWorkers,Number(document.querySelector('[data-testid="active-worker-count"]')?.textContent)||0);},250);
  window.Worker=class extends Original{constructor(url,options){super(url,options);this.isQr=String(url).includes('qr.worker');}postMessage(message,transfer){if(this.isQr)window.__qrMessages++;super.postMessage(message,transfer);}};
 });
 page.on('pageerror',e=>report.errors.push(e.message));page.on('request',r=>{if(/^https?:/.test(r.url()))report.remoteRequests.push(r.url());});
 await page.exposeFunction('goldInput',async index=>(await readFile(manifest[index].path)).toString('base64'));
 const assets=await readdir(root+'/dist/assets');
 for(const batch of [...new Set(manifest.map(x=>x.batch))]){
  const photos=manifest.filter(x=>x.batch===batch),pairs=expected.filter(x=>x.batch===batch),indices=photos.map(x=>manifest.indexOf(x));
  await page.evaluate(()=>{globalThis.__name=x=>x;});
  await installOpfsPicker({addInitScript:(fn,arg)=>page.evaluate(fn,arg)},batch);
  const seed=Date.now();
  await page.evaluate(async ({batch,indices,names})=>{
   const folder=await(await navigator.storage.getDirectory()).getDirectoryHandle(batch,{create:true});let next=0;
   await Promise.all(Array.from({length:4},async()=>{while(next<indices.length){const i=next++;const b64=await window.goldInput(indices[i]);const handle=await folder.getFileHandle(names[i],{create:true});const out=await handle.createWritable();await out.write(Uint8Array.from(atob(b64),x=>x.charCodeAt(0)));await out.close();}}));
  },{batch,indices,names:photos.map(x=>x.name)});
  await page.getByRole('button',{name:'选择照片文件夹',exact:true}).click();
  await expect(page.getByTestId('photo-row')).toHaveCount(Math.min(100,photos.length),{timeout:60000});
  console.log(JSON.stringify({phase:'imported',batch,count:photos.length,ms:Date.now()-seed}));
  const scanStart=Date.now();await page.getByRole('button',{name:/^(?:开始|继续|重新)识别$/,exact:true}).click();
  await expect(page.getByRole('button',{name:'重新识别',exact:true})).toBeEnabled({timeout:360000});
  const scanMs=Date.now()-scanStart;
  const peakScanWorkers=await page.evaluate(()=>window.__peakWorkers);
  await expect(page.getByTestId('pair-row')).toHaveCount(pairs.length);
  const actual=await page.getByTestId('pair-row').evaluateAll(rows=>rows.map(row=>({certId:row.querySelector('input[aria-label^="证书号"]').value,front:row.querySelector('[data-testid="front-source"]').textContent.trim(),back:row.querySelector('[data-testid="back-source"]').textContent.trim()})));
  assert.deepEqual(actual,pairs.map(({front,back,certId})=>({certId,front,back})));
  await expect(page.locator('.review-link')).toContainText('待检查 0 张');
  console.log(JSON.stringify({phase:'paired',batch,correctPairs:pairs.length,scanMs,peakScanWorkers}));
  if(process.env.NXR_CORPUS_SCREENSHOTS === '1') await page.screenshot({path:output+`/${batch}-recognized.png`,fullPage:true});
  const warmStart=Date.now();await page.reload();await page.waitForURL('nxr://app/');
  await page.evaluate(()=>{globalThis.__name=x=>x;});
  await installOpfsPicker({addInitScript:(fn,arg)=>page.evaluate(fn,arg)},batch);
  await page.getByRole('button',{name:'选择照片文件夹',exact:true}).click();
  await expect(page.getByTestId('pair-row')).toHaveCount(pairs.length,{timeout:60000});
  const warmLoadMs=Date.now()-warmStart;
  assert.equal(await page.evaluate(()=>window.__qrMessages),0);
  console.log(JSON.stringify({phase:'cache-reopen',batch,correctPairs:pairs.length,warmLoadMs,workerScans:0}));
  const renameStart=Date.now();await page.getByRole('button',{name:'执行改名',exact:true}).click();await page.getByRole('button',{name:'确认改名',exact:true}).click();
  await expect(page.locator('.progress-strip')).toHaveCount(0,{timeout:360000});
  await expect(page.getByRole('status').filter({hasText:`已完成 ${photos.length} 张图片改名`})).toBeVisible();
  const renameMs=Date.now()-renameStart;
  const outputs=await directoryFiles(page,batch);assert.equal(Object.keys(outputs).filter(x=>x.endsWith('.webp')).length,photos.length);
  console.log(JSON.stringify({phase:'converted',batch,outputs:photos.length,renameMs}));
  const checks=await page.evaluate(async ({batch,pairs,photos,assets})=>{
   const folder=await(await navigator.storage.getDirectory()).getDirectoryHandle(batch);let next=0;const results=[];
   await Promise.all(Array.from({length:Math.min(14,pairs.length)},async()=>{
    const worker=new Worker(`nxr://app/assets/${assets.find(x=>x.startsWith('qr.worker-')&&x.endsWith('.js'))}`,{type:'module'});
    try{while(next<pairs.length){const i=next++,pair=pairs[i];
     for(const side of ['A','B']){const file=await(await folder.getFileHandle(`${pair.certId}_${side}.webp`)).getFile();const bitmap=await createImageBitmap(file);const dims=[bitmap.width,bitmap.height];bitmap.close();const expected=photos.find(x=>x.name===(side==='A'?pair.front:pair.back)).size;
      if(dims[0]!==expected[0]||dims[1]!==expected[1])throw new Error('dimensions changed');
      if(side==='B'){const decoded=await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('output QR timeout')),65000);worker.onmessage=e=>{clearTimeout(timer);resolve(e.data);};worker.onerror=e=>{clearTimeout(timer);reject(new Error(e.message));};worker.postMessage({id:i,file,mode:'standard'});});results[i]={name:file.name,certIds:decoded.certIds,error:decoded.error,expected:pair.certId};}
     }
    }}finally{worker.terminate();}
   }));return results;
  },{batch,pairs,photos,assets});
  const bad=checks.filter(x=>x.error||x.certIds.length!==1||x.certIds[0]!==x.expected);
  await writeFile(output+`/${batch}-webp-checks.json`,JSON.stringify(checks,null,2));assert.deepEqual(bad,[]);
  console.log(JSON.stringify({phase:'webp-redecoded',batch,correct:pairs.length}));
  const reviewClose=page.getByRole('button',{name:'关闭图片检查',exact:true});
  if(await reviewClose.isVisible()) await reviewClose.click();
  const restoreStart=Date.now();await page.getByRole('button',{name:'恢复文件名',exact:true}).click();await page.getByRole('button',{name:'确认恢复',exact:true}).click();
  await expect(page.locator('.progress-strip')).toHaveCount(0,{timeout:180000});
  const restored=await directoryFiles(page,batch);for(const photo of photos)assert.equal(restored[photo.name].sha256,photo.sha256,photo.name);
  assert.equal(Object.keys(restored).filter(x=>x.endsWith('.webp')).length,0);
  report.batches.push({batch,photos:photos.length,correctPairs:pairs.length,scanMs,peakScanWorkers,warmLoadMs,renameMs,restoreMs:Date.now()-restoreStart,webpBacksCorrect:checks.length,dimensionsPreserved:true,restoredOriginalBytes:true});
  await writeFile(output+'/acceptance.json',JSON.stringify(report,null,2));console.log(JSON.stringify({phase:'restored',batch,...report.batches.at(-1)}));
 }
 for(const photo of manifest)assert.equal(createHash('sha256').update(await readFile(photo.path)).digest('hex'),photo.sha256);
 report.originalsUnchanged=true;assert.deepEqual(report.errors,[]);assert.deepEqual(report.remoteRequests,[]);
 await writeFile(output+'/acceptance.json',JSON.stringify(report,null,2));console.log('ALL_CORPUS_CHECKS_PASSED');
}finally{if(app){await app.evaluate(({dialog})=>{dialog.showMessageBoxSync=()=>1;}).catch(()=>{});await app.close();}await rm(profile,{recursive:true,force:true});}
