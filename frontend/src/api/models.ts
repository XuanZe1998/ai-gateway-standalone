// 文件说明：models：前端模块，集中维护相关类型、状态或交互逻辑。
import request from '@/utils/request'
import type { RouterResponse } from '@/types'

// 模型实例接口
export interface ModelInstance {
  id: string | number
  serviceType: string
  name: string
  baseUrl: string
  path: string
  weight: number
  status: 'active' | 'inactive'
  instanceId: string
  adapter?: string
}

// 获取指定服务类型的模型列表（从实例管理中获取）
export const getModelsByServiceType = async (serviceType: string): Promise<string[]> => {
  const { getCatalog } = await import('@/api/selfService')
  try { return (await getCatalog()).filter(m => m.service_type === serviceType).map(m => m.id) }
  catch { return [] }
}
export const getAllModels = async (): Promise<Record<string, string[]>> => {
  const { getCatalog } = await import('@/api/selfService')
  try {
    const result: Record<string, string[]> = {}
    for (const model of await getCatalog()) (result[model.service_type] ??= []).push(model.id)
    return result
  } catch { return {} }
}

// 服务类型到playground类型的映射
export const serviceTypeMapping: Record<string, string[]> = {
  chat: ['chat'],
  embedding: ['embedding'],
  rerank: ['rerank'],
  tts: ['tts'],
  stt: ['stt'],
  imgGen: ['imageGenerate'],
  imgEdit: ['imageEdit']
}

// 根据playground服务类型获取对应的实例管理服务类型
export const getInstanceServiceType = (playgroundType: string): string => {
  for (const [instanceType, playgroundTypes] of Object.entries(serviceTypeMapping)) {
    if (playgroundTypes.includes(playgroundType)) {
      return instanceType
    }
  }
  return playgroundType // 如果没有映射，直接返回原类型
}