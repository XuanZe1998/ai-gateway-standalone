import request from '@/utils/request'

export interface CampusKey {
  keyId: string
  ownerId: number
  platformUserId: string
  name: string
  status: 'ACTIVE' | 'DISABLED' | 'REVOKED'
  createdAt: string
  updatedAt: string
  expiresAt: string | null
}
export interface IssuedKey { key: CampusKey; secret: string }
export interface KeyList { keys: CampusKey[]; limit: number; count: number }
export interface BalanceInfo {
  verified: boolean
  quota: { exists: boolean; total?: number; used?: number; remaining?: number; periodEnd?: string }
  paid: { exists: boolean; shared: boolean; balance?: number }
}
export interface UsageInfo {
  summary: { requests: number; tokens: number; cost: number }
  records: Array<{ id: number; model: string; service: string; key_id: string; tokens: number; cost: number; success: boolean; time: string }>
  page: number
  size: number
}
export interface CatalogModel { id: string; service_type: string }
export const getKeys = () => request.get<KeyList>('/me/keys').then(r => r.data)
export const createKey = (name: string) => request.post<IssuedKey>('/me/keys', { name }).then(r => r.data)
export const rotateKey = (id: string) => request.post<IssuedKey>(`/me/keys/${encodeURIComponent(id)}/rotate`).then(r => r.data)
export const changeKey = (id: string, status: CampusKey['status']) => request.patch<CampusKey>(`/me/keys/${encodeURIComponent(id)}`, { status }).then(r => r.data)
export const getBalance = () => request.get<BalanceInfo>('/me/balance').then(r => r.data)
export const getUsage = (params?: Record<string, string | number | undefined>) => request.get<UsageInfo>('/me/usage', { params }).then(r => r.data)
export const getCatalog = () => request.get<{ data: { data: CatalogModel[] } }>('/models').then(r => r.data.data.data)
