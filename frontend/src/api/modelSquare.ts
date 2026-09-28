import request from '@/utils/request'
export interface PriceLine { item: string; unit: string; standardPrice: number | null; effectivePrice: number | null; status: 'CHARGED' | 'NOT_CHARGED' | 'IN_OUTPUT' | 'UNKNOWN'; condition: string }
export interface Discounts { modelDiscountRate: number; userDiscountRate: number; enterpriseDiscountRate: number; finalDiscountRate: number }
export interface PricingScheme { label: string; mode: string; configured: boolean; discounts: Discounts | null; lines: PriceLine[]; notes: string[] }
export interface ModelCard { serviceType: string; modelId: string; displayName: string; description: string; tags: string[]; vendors: string[]; schemeCount: number; priceStatus: 'MULTIPLE' | 'UNKNOWN' | 'CONFIGURED'; priceSummary: PriceLine[]; freeQuotaApplicable: boolean; overridden: boolean }
export interface ModelAccess { method: string; path: string; contentType: string; queryPath: string | null; note: string }
export interface ModelDetail { model: ModelCard; schemes: PricingScheme[]; access: ModelAccess; notes: string[] }
export interface ModelContent { serviceType: string; modelId: string; displayName: string | null; description: string | null; tags: string[] }
export interface ContentView { model: ModelCard; defaultName: string; defaultDescription: string; defaultTags: string[]; override: ModelContent | null; updatedAt: string | null; updatedBy: string | null }
export const getModelSquare = () => request.get<ModelCard[]>('/model-square').then(r => r.data)
export const getModelDetail = (serviceType: string, modelId: string) => request.get<ModelDetail>('/model-square/detail', { params: { serviceType, modelId } }).then(r => r.data)
export const getModelContent = () => request.get<ContentView[]>('/admin/model-square/content').then(r => r.data)
export const saveModelContent = (body: ModelContent) => request.put<ContentView>('/admin/model-square/content', body).then(r => r.data)
export const resetModelContent = (serviceType: string, modelId: string) => request.delete<boolean>('/admin/model-square/content', { params: { serviceType, modelId } }).then(r => r.data)
