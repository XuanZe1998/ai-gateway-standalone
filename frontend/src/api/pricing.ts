import request from '@/utils/request'

export interface PricingRow {
  serviceType: string
  modelId: string
  platformModelId: number | null
  vendor: string | null
  billingMode: number | null
  inputPrice: number | null
  cacheHitInputPrice: number | null
  outputPrice: number | null
  thinkingPrice: number | null
  tierCount: number
  configured: boolean
}

export interface PricingEditable {
  serviceType: string
  modelId: string
  vendor: string | null
  billingMode: number | null
  inputPrice: number | null
  cacheHitInputPrice: number | null
  outputPrice: number | null
  cacheCreateInputPrice: number | null
  cacheHitExplicitInputPrice: number | null
  enableInputToken: boolean | null
  enableCacheHitInput: boolean | null
  enableOutputToken: boolean | null
  enableCacheCreateInput: boolean | null
  enableCacheHitExplicitInput: boolean | null
  thinkingBillingMode: number | null
  thinkingPrice: number | null
  discount: number | null
}

export interface PricingTier {
  id: number | null
  tierOrderNo: number
  lowerLimitK: number | null
  upperLimitK: number | null
  unlimited: boolean
  inputPrice: number | null
  cacheHitInputPrice: number | null
  outputPrice: number | null
  cacheCreateInputPrice: number | null
  cacheHitExplicitInputPrice: number | null
  thinkingPrice: number | null
}

export interface PricingDetail {
  editable: PricingEditable
  tiers: PricingTier[]
}

/** 手动保存主定价的载荷与 PricingEditable 结构一致 */
export type PricingSavePayload = PricingEditable

export const getPricingList = () => request.get<PricingRow[]>('/admin/pricing').then(r => r.data)
export const getPricingDetail = (serviceType: string, modelId: string) =>
  request.get<PricingDetail>('/admin/pricing/detail', { params: { serviceType, modelId } }).then(r => r.data)
export const savePricing = (body: PricingSavePayload) =>
  request.put<PricingRow>('/admin/pricing', body).then(r => r.data)
export const savePricingTiers = (serviceType: string, modelId: string, tiers: PricingTier[]) =>
  request.put<PricingRow>('/admin/pricing/tiers', { serviceType, modelId, tiers }).then(r => r.data)
export const deletePricing = (serviceType: string, modelId: string) =>
  request.delete<boolean>('/admin/pricing', { params: { serviceType, modelId } }).then(r => r.data)


// ===== 计费策略定义（层次A）=====
export type PricingStrategyKey =
  | 'normalPrice' | 'output' | 'cacheHit' | 'cacheCreate' | 'cacheHitExplicit' | 'thinking' | 'discount'

export interface PricingStrategyDef {
  key: PricingStrategyKey
  label: string
  hint: string
  unit?: string
  kind: 'price' | 'thinking' | 'discount'
}

/** 策略 -> ai_model 六维字段 的映射 */
export const STRATEGY_FIELDS: Record<string, { price: keyof PricingSavePayload; enable?: keyof PricingSavePayload }> = {
  normalPrice: { price: 'inputPrice', enable: 'enableInputToken' },
  output: { price: 'outputPrice', enable: 'enableOutputToken' },
  cacheHit: { price: 'cacheHitInputPrice', enable: 'enableCacheHitInput' },
  cacheCreate: { price: 'cacheCreateInputPrice', enable: 'enableCacheCreateInput' },
  cacheHitExplicit: { price: 'cacheHitExplicitInputPrice', enable: 'enableCacheHitExplicitInput' },
  thinking: { price: 'thinkingPrice' },
  discount: { price: 'discount' }
}

export const PRICING_STRATEGIES: Record<PricingStrategyKey, PricingStrategyDef> = {
  normalPrice: { key: 'normalPrice', label: '普通输入（未命中缓存）', hint: '输入 token 扣除各类缓存后的净量', unit: '元/M', kind: 'price' },
  output: { key: 'output', label: '输出', hint: '输出 token（思考并入输出时不单独计价）', unit: '元/M', kind: 'price' },
  cacheHit: { key: 'cacheHit', label: '缓存命中输入', hint: '隐式缓存命中（DeepSeek/OpenAI）', unit: '元/M', kind: 'price' },
  cacheCreate: { key: 'cacheCreate', label: '显式缓存创建', hint: 'Anthropic cache_creation', unit: '元/M', kind: 'price' },
  cacheHitExplicit: { key: 'cacheHitExplicit', label: '显式缓存命中', hint: 'Anthropic cache_read', unit: '元/M', kind: 'price' },
  thinking: { key: 'thinking', label: '思考 Token', hint: '深度思考模型的推理消耗', unit: '元/M', kind: 'thinking' },
  discount: { key: 'discount', label: '折扣（模型级）', hint: '0-100，100 不打折', kind: 'discount' }
}

/** 按模型类型返回可添加的策略（层次A：token 类共用同一组，文案区分） */
export const STRATEGY_GROUP: Record<string, { label: string; keys: PricingStrategyKey[] }> = {
  chat: { label: '文本计费', keys: ['normalPrice','output','cacheHit','cacheCreate','cacheHitExplicit','thinking','discount'] },
  embedding: { label: '文本计费', keys: ['normalPrice','output','cacheHit','cacheCreate','cacheHitExplicit','thinking','discount'] },
  rerank: { label: '文本计费', keys: ['normalPrice','output','cacheHit','cacheCreate','cacheHitExplicit','thinking','discount'] },
  tts: { label: '语音计费', keys: ['normalPrice','output','cacheHit','cacheCreate','cacheHitExplicit','thinking','discount'] },
  stt: { label: '语音计费', keys: ['normalPrice','output','cacheHit','cacheCreate','cacheHitExplicit','thinking','discount'] },
  imgGen: { label: '图像计费', keys: ['normalPrice','output','cacheHit','cacheCreate','cacheHitExplicit','thinking','discount'] },
  imgEdit: { label: '图像计费', keys: ['normalPrice','output','cacheHit','cacheCreate','cacheHitExplicit','thinking','discount'] }
}

