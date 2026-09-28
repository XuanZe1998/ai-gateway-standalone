import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'
const src = await readFile(new URL('../src/utils/modelSquare.ts', import.meta.url), 'utf8')
const js = ts.transpileModule(src, { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.ES2020 } }).outputText
const { filterModels, formatPrice, priceText, connectionExample, copyText } = await import('data:text/javascript;base64,' + Buffer.from(js).toString('base64'))
const paths = {chat:'/v1/chat/completions',embedding:'/v1/embeddings',rerank:'/v1/rerank',tts:'/v1/audio/speech',stt:'/v1/audio/transcriptions',imgGen:'/v1/images/generations',imgEdit:'/v1/images/edits',vidGen:'/v1/videos/generations'}
for (const [service, path] of Object.entries(paths)) test(`correct ${service} example and placeholders`, () => {
  const sample = connectionExample({model:{serviceType:service,modelId:'real-id'},access:{method:'POST',path,queryPath:service==='vidGen'?path+'/task/{taskId}':null}},'https://example.test')
  assert.ok(sample.includes('https://example.test'+path));assert.ok(sample.includes('YOUR_KEY'));assert.ok(sample.includes('real-id'))
  assert.ok(sample.includes(' \\\n  -H'))
  if (['stt','imgEdit'].includes(service)) {assert.ok(sample.includes('-F'));assert.ok(!sample.includes('application/json'))}
  else {assert.ok(sample.includes('application/json'));assert.ok(sample.includes('"model":"real-id"'))}
  if (service==='vidGen') {assert.ok(sample.includes('/task/TASK_ID'));assert.ok(sample.includes('"content":'))}
  if (service==='tts') assert.ok(sample.includes('--output speech.mp3'))
})
test('shell quote handles apostrophes without executable substitutions',()=>{
  const s=connectionExample({model:{serviceType:'chat',modelId:"model'$(id)"},access:{method:'POST',path:'/v1/chat/completions'}},'https://example.test')
  assert.ok(s.includes("model'\"'\"'$(id)"))
})
test('filter matches name/id, real vendor/service, stable sort and preserves input',()=>{
  const models=[{displayName:'B',modelId:'2',serviceType:'chat',vendors:['A']},{displayName:'A',modelId:'1',serviceType:'embedding',vendors:['B']},{displayName:'A',modelId:'0',serviceType:'chat',vendors:['A']}]
  assert.deepEqual(filterModels(models,'','','').map(m=>m.modelId),['0','1','2']);assert.equal(models[0].modelId,'2')
  assert.deepEqual(filterModels(models,' b ','chat','A').map(m=>m.modelId),['2']);assert.equal(filterModels(models,'1','','')[0].serviceType,'embedding')
  assert.equal(filterModels(models,'none','','').length,0)
})
test('unknown does not appear free and zero is explicit',()=>{
  assert.equal(formatPrice(null),'暂不可确认');assert.equal(formatPrice(0),'0');assert.equal(formatPrice(0.00001),'0.00001')
  assert.equal(priceText({status:'UNKNOWN',effectivePrice:0}),'未配置');assert.equal(priceText({status:'CHARGED',effectivePrice:0}),'¥ 0');assert.equal(priceText({status:'IN_OUTPUT'}),'并入输出');assert.equal(priceText({status:'NOT_CHARGED'}),'不计费')
})
test('clipboard success, failure and unavailable are distinguishable',async()=>{
  let seen;assert.equal(await copyText('value',{writeText:async text=>{seen=text}}),true);assert.equal(seen,'value')
  assert.equal(await copyText('value',{writeText:async()=>{throw new Error('denied')}}),false)
  assert.equal(await copyText('value',null),false)
})
