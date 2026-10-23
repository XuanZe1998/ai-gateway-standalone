import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile, access } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import ts from 'typescript'

const libraryRoot = new URL('../../src/main/resources/static/model-icons/', import.meta.url)
const catalog = JSON.parse(await readFile(new URL('catalog.json', libraryRoot), 'utf8'))
let src = await readFile(new URL('../src/utils/modelIcons.ts', import.meta.url), 'utf8')
src = src.replace(/^import catalog[^\n]+/m, `const catalog = ${JSON.stringify(catalog)}`)
src = src.replace(/const assets = import\.meta\.glob\([\s\S]*?as Record<string, string>/, `const assets = ${JSON.stringify(Object.fromEntries(catalog.icons.map(icon => ['../../../src/main/resources/static/model-icons/' + icon.file, '/assets/' + icon.file])))}`)
const js = ts.transpileModule(src, { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.ES2020 } }).outputText
const { modelIconId, modelIconUrl, automaticModelIconId } = await import('data:text/javascript;base64,' + Buffer.from(js).toString('base64'))
const model = (modelId, serviceType = 'chat', extra = {}) => ({ modelId, serviceType, displayName: modelId, vendors: [], ...extra })

test('model brand wins over routing provider, namespace and distillation are handled', () => {
  assert.equal(modelIconId(model('deepseek-ai/DeepSeek-R1-Distill-Qwen-32B', 'chat', { vendors: ['OpenAI'] })), 'deepseek')
  assert.equal(modelIconId(model('Qwen/Qwen3-32B')), 'qwen')
  assert.equal(modelIconId(model('Doubao-seedream-4.5', 'imgGen')), 'doubao')
  assert.equal(modelIconId(model('MiniMax-H3', 'vidGen')), 'minimax')
  assert.equal(modelIconId(model('text-embedding-v4', 'embedding')), 'qwen')
  assert.equal(modelIconId(model('text-embedding-3-large', 'embedding')), 'openai')
  assert.equal(modelIconId(model('wan3.0-video', 'vidGen')), 'wan')
})
test('manual override wins, empty or invalid ID restores the automatic local icon', () => {
  assert.equal(modelIconId(model('qwen-plus', 'chat', { iconId: 'kimi' })), 'kimi')
  assert.equal(modelIconId(model('qwen-plus', 'chat', { iconId: '' })), 'qwen')
  assert.equal(modelIconId(model('qwen-plus', 'chat', { iconId: 'https://example.com/evil.svg' })), 'qwen')
  assert.equal(modelIconId(model('orb_base_60_std_zj')), 'generic-chat')
  assert.equal(modelIconId(model('orb_base_60_std_zj', 'chat', { displayName: 'Claude', vendors: ['OpenAI'] })), 'generic-chat')
  assert.equal(modelIconId(model('local', 'stt')), 'generic-stt')
})
test('all assets are local valid SVGs with recorded hashes, and all IDs are unique', async () => {
  const sources = JSON.parse(await readFile(new URL('sources.json', libraryRoot), 'utf8'))
  assert.equal(new Set(catalog.icons.map(icon => icon.id)).size, catalog.icons.length)
  for (const icon of catalog.icons) {
    const bytes = await readFile(new URL(icon.file, libraryRoot)), svg = bytes.toString()
    assert.match(svg, /<svg\b/)
    assert.match(svg, /viewBox=/)
    assert.doesNotMatch(svg, /<script|<foreignObject|\son\w+=|\shref=/i)
    assert.equal(createHash('sha256').update(bytes).digest('hex'), sources.find(source => source.id === icon.id).sha256)
    assert.equal(modelIconUrl(icon.id), '/assets/' + icon.file)
  }
  await access(new URL('LICENSE-LobeHub', libraryRoot))
  await access(new URL('LICENSE-Lucide', libraryRoot))
})
test('every model in the saved platform inventory resolves to an installed icon', async () => {
  const inventory = JSON.parse(await readFile(new URL('../../docs/model-square-icon-assignments-20261014.json', import.meta.url), 'utf8'))
  for (const item of inventory.models) {
    const id = automaticModelIconId(model(item.modelId, item.serviceType))
    assert.ok(catalog.icons.some(icon => icon.id === id), item.modelId)
    assert.ok(modelIconUrl(id), item.modelId)
    assert.equal(id, item.iconId, item.modelId)
    assert.equal(id.startsWith('generic-'), item.status === 'deferred', item.modelId)
  }
})
