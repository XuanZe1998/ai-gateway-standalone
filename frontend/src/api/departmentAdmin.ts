import request from '@/utils/request'
import { apiPayload } from '@/utils/apiPayload'

export interface MemberFilters { identityType?: string; role?: string; accountType?: number; enabled?: boolean }
export interface DirectoryOptions { identities: { value: string; label: string }[]; roles: string[] }
export interface BudgetUsage { tokens: number; cost: number; value: number; unknown_value: number; unknown_active_value: number }
export interface UsageFilters extends MemberFilters { start: string; end: string; department?: string; search?: string; departmentSearch?: string; model?: string; service?: string; freeUsage?: boolean; currentFreeUsage?: boolean; sort: string }
export interface UsageRow { id: string; name: string; owner_id?: number; user_id?: string; display_name?: string; account?: string; current_member?: boolean; free_usage?: boolean; funding_source?: string; policy_source?: string; monthly_cost?: number; monthly_tokens?: number; budgetUsage?: BudgetUsage; usage_value?: number; value_unknown?: number; requests: number; tokens: number; cost: number; deducted: number; pending: number; active?: number; user_count?: number; free_user_count?: number; input_tokens?: number; output_tokens?: number; cache_tokens?: number; accountInfo?: PoolAccount; policy?: DepartmentPolicy; monthlyUsage?: { tokens: number; cost: number }; alerts?: Alert[]; [key: string]: unknown }
export interface PoolAccount { balance: number; allocated_total: number; reserved_amount: number; available: number }
export interface DepartmentPolicy { free_usage?: boolean; funding_source?: string; monthly_tokens?: number | null; member_cost_limit?: number | null; member_token_limit?: number | null; alert_thresholds?: number[]; version?: number }
export interface Alert { department_id: string; message: string; metric: string; threshold: number; created_at: string }
export interface Operation { id: string; kind: string; operator: string; status: string; reason: string; result: string; created_at: string }
export interface DepartmentDetail { id: string; account: PoolAccount; policy: DepartmentPolicy; monthlyUsage: { tokens: number; cost: number }; alerts: Alert[]; operations: Operation[]; reconciliation: Record<string, any>[] }
export interface UsageReport { records: UsageRow[]; total: number; summary: { tokens: number; cost: number; deducted: number; requests: number; active: number; members: number; unused: number; pending: number; usage_value: number; value_unknown: number } }
export interface BatchRequest extends MemberFilters { departmentId: string; kind: string; ownerIds?: number[]; search?: string; departmentSearch?: string; selectionFreeUsage?: boolean; freeUsage?: boolean; fundingSource?: string; amount?: number; monthlyTokens?: number | null; monthlyCost?: number | null; memberTokens?: number | null; alertThresholds?: number[]; overwriteExceptions?: boolean; reason: string }
export interface BatchPreview { id: string; count: number; totalAmount: number; targets: Record<string, unknown>[]; accounts: { accountId: string; accountType: number; shared: boolean; balance: number }[]; payload: BatchRequest }
export interface UserDetail { identity?: Record<string, unknown>; budgetUsage?: BudgetUsage; profile: Record<string, any>; trends: Record<string, any>[]; models: Record<string, any>[]; keys: Record<string, any>[]; bills: Record<string, any>[]; billTotal: number; quota: Record<string, any>[]; account: Record<string, any>[] }
export function limitedBudgetError(request: BatchRequest) { return request.kind === 'FREE_LIMITED' && request.memberTokens == null && request.monthlyCost == null ? '请至少填写每人每月 Token 或使用金额上限' : '' }
export function batchSelection(filters: UsageFilters, ownerIds?: number[]) {
  return { ownerIds, search: filters.search, departmentSearch: filters.department ? undefined : filters.departmentSearch, identityType: filters.identityType, role: filters.role, accountType: filters.accountType, enabled: filters.enabled, selectionFreeUsage: filters.currentFreeUsage }
}
const base = '/admin/departments'
export async function getDirectoryOptions() { return apiPayload<DirectoryOptions>((await request.get(`${base}/options`)).data) }
export async function getReport(filters: UsageFilters, members: boolean, page: number, size: number) { return apiPayload<UsageReport>((await request.get(`${base}/report`, { params: { ...filters, members, page, size } })).data) }
export async function getDepartment(department: string) { return apiPayload<DepartmentDetail>((await request.get(`${base}/detail`, { params: { department } })).data) }
export async function getUser(owner: number, filters: UsageFilters, billPage = 0, billSize = 20) { return apiPayload<UserDetail>((await request.get(`${base}/users/${owner}`, { params: { ...filters, billPage, billSize } })).data) }
export async function getAlerts(department?: string) { return apiPayload<Alert[]>((await request.get(`${base}/alerts`, { params: { department } })).data) }
export async function previewBatch(payload: BatchRequest) { return apiPayload<BatchPreview>((await request.post(`${base}/batches/preview`, payload)).data) }
export async function commitBatch(id: string) { return apiPayload<{ id: string; status: string; success: number; failed: number }>((await request.post(`${base}/batches/${id}/commit`)).data) }
export async function retrySettlement(id: string, reason: string) { return apiPayload<{ settled: boolean }>((await request.post(`${base}/reservations/${id}/retry`, { reason })).data) }
export async function exportReport(filters: UsageFilters, members: boolean) {
  let response
  try { response = await request.get(`${base}/export`, { params: { ...filters, members }, responseType: 'blob' }) }
  catch (error: any) { if (error.response?.data instanceof Blob) { let body: any; try { body = JSON.parse(await error.response.data.text()) } catch {} if (body?.message) throw new Error(body.message) } throw error }
  const url = URL.createObjectURL(response.data)
  const a = document.createElement('a'); a.href = url; a.download = `部门${members ? '成员' : '汇总'}用量-${filters.start}-${filters.end}.csv`; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
export function beijingDate(date = new Date()) { return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(date) }
export function dateRange(preset: 'today' | 'week' | 'month'): [string, string] {
  const end = beijingDate(), today = new Date(`${end}T00:00:00+08:00`)
  return [preset === 'today' ? end : preset === 'month' ? `${end.slice(0, 7)}-01` : beijingDate(new Date(today.getTime() - 6 * 86400000)), end]
}
export const formatTokens = (value: unknown) => Number(value || 0).toLocaleString('zh-CN')
export const formatMoney = (value: unknown) => Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 8 })
export const policySource = (value?: string) => ({ PERSONAL: '个人例外', DEPARTMENT: '继承部门', PLATFORM: '平台默认', HISTORICAL: '历史记录' }[value || ''] || '—')
export const fundingSource = (value?: string) => value === 'DEPARTMENT' ? '部门共享池' : value === 'PERSONAL' ? '个人／企业账户' : '历史未知'
export const settlementLabel = (value?: string) => ({ SETTLED: '已扣费', EXEMPT: '无需扣费', PENDING: '待结算', RECONCILE: '待核对', FAILED: '扣费失败', UNKNOWN: '历史未确认' }[value || ''] || '未知')
