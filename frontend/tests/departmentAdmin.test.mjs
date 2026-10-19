import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'
const source = await readFile(new URL('../src/api/departmentAdmin.ts', import.meta.url), 'utf8')
function harness() {
  const calls = [], module = { exports: {} }
  const request = { get: async (url, options) => { calls.push({ url, options }); return { data: { success: true, data: { records: [], total: 0 } } } }, post: async (url, body) => { calls.push({ url, body }); return { data: { success: true, data: { id: 'batch-1' } } } } }
  class FixedDate extends Date { constructor(...args) { super(...(args.length ? args : ['2026-10-18T17:20:00Z'])) } }
  const imports = { '@/utils/request': { default: request }, '@/utils/apiPayload': { apiPayload: body => body.data } }
  new Function('require', 'module', 'exports', 'Date', ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS } }).outputText)(name => imports[name], module, module.exports, FixedDate)
  return { api: module.exports, calls }
}
test('date shortcuts follow Beijing calendar across the UTC date boundary', () => {
  const { api } = harness()
  assert.equal(api.beijingDate(), '2026-10-19')
  assert.deepEqual(api.dateRange('today'), ['2026-10-19', '2026-10-19'])
  assert.deepEqual(api.dateRange('week'), ['2026-10-13', '2026-10-19'])
  assert.deepEqual(api.dateRange('month'), ['2026-10-01', '2026-10-19'])
})
test('member report and individual bills preserve identical filters and separate pagination', async () => {
  const { api, calls } = harness()
  const filters = { start: '2026-10-01', end: '2026-10-19', department: 'code:研发/一部', search: '张', model: 'qwen', freeUsage: false, sort: 'tokens' }
  await api.getReport(filters, true, 2, 20)
  assert.deepEqual(calls[0].options.params, { ...filters, members: true, page: 2, size: 20 })
  await api.getUser(9, filters, 3, 50)
  assert.deepEqual(calls[1].options.params, { ...filters, billPage: 3, billSize: 50 })
})
test('all matching members are not reduced to the current report page and retries reuse batch identity', async () => {
  const { api, calls } = harness()
  const payload = { departmentId: 'code:D1', kind: 'ALLOCATE', search: '老师', selectionFreeUsage: true, amount: 100, monthlyTokens: null, reason: '教学支持' }
  await api.previewBatch(payload)
  assert.equal(calls[0].body.ownerIds, undefined)
  assert.equal(calls[0].body.page, undefined)
  await api.commitBatch('batch-1'); await api.commitBatch('batch-1')
  assert.equal(calls[1].url, calls[2].url)
})
test('small charges retain eight decimal places and unsettled bills are labelled explicitly', () => {
  const { api } = harness()
  assert.equal(api.formatMoney('0.00000407'), '0.00000407')
  assert.equal(api.settlementLabel('RECONCILE'), '待核对')
  assert.equal(api.settlementLabel('UNKNOWN'), '历史未确认')
  assert.equal(api.policySource('PERSONAL'), '个人例外')
})

test('cross-department free grants preserve directory dimensions and exclude bill-only filters', async () => {
  const { api, calls } = harness()
  const filters = { start: '2026-10-01', end: '2026-10-19', departmentSearch: '学院', identityType: 'code:T', role: 'TEACHER', accountType: 2, enabled: true, currentFreeUsage: false, model: 'model', service: 'chat', freeUsage: true, sort: 'tokens' }
  const selection = api.batchSelection(filters)
  assert.equal(selection.ownerIds, undefined)
  assert.equal(selection.selectionFreeUsage, false)
  assert.equal(selection.identityType, 'code:T')
  assert.equal(selection.role, 'TEACHER')
  assert.equal(selection.accountType, 2)
  assert.equal(selection.enabled, true)
  assert.equal(selection.departmentSearch, '学院')
  for (const field of ['model', 'service', 'start', 'end', 'freeUsage']) assert.equal(field in selection, false)
  await api.previewBatch({ departmentId: 'all:members', kind: 'FREE_LIMITED', ...selection, freeUsage: true, fundingSource: 'PERSONAL', memberTokens: 1000000, reason: '教学支持' })
  assert.equal(calls[0].body.memberTokens, 1000000)
  assert.equal(calls[0].body.selectionFreeUsage, false)
  assert.deepEqual(api.batchSelection({ ...filters, department: 'code:D1' }, [1, 2]).ownerIds, [1, 2])
  assert.equal(api.batchSelection({ ...filters, department: 'code:D1' }).departmentSearch, undefined)
})

test('limited free usage accepts money only, tokens only or both, including an explicit zero limit', async () => {
  const { api, calls } = harness()
  const base = { departmentId: 'all:members', kind: 'FREE_LIMITED', freeUsage: true, fundingSource: 'PERSONAL', reason: '免费金额预算' }
  assert.ok(api.limitedBudgetError({ ...base, memberTokens: null, monthlyCost: null }))
  for (const limits of [{ monthlyCost: 100, memberTokens: null }, { monthlyCost: null, memberTokens: 1000000 }, { monthlyCost: 100, memberTokens: 1000000 }, { monthlyCost: 0, memberTokens: null }]) {
    const payload = { ...base, ...limits }
    assert.equal(api.limitedBudgetError(payload), '')
    await api.previewBatch(payload)
    assert.equal(calls.at(-1).body.monthlyCost, limits.monthlyCost)
    assert.equal(calls.at(-1).body.memberTokens, limits.memberTokens)
  }
})
