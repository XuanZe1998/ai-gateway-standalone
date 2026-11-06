import catalog from '../../../src/main/resources/static/model-icons/catalog.json'

export const modelIcons = catalog.icons
const assets = import.meta.glob('../../../src/main/resources/static/model-icons/*.svg', {
  eager: true, query: '?url', import: 'default'
}) as Record<string, string>
const rules = modelIcons.filter(icon => icon.modelPattern).map(icon => ({
  id: icon.id, model: new RegExp(icon.modelPattern, 'i'), brand: new RegExp(icon.brandPattern, 'i')
}))
export interface IconModel { modelId: string; serviceType: string; displayName: string; vendors: string[]; iconId?: string | null }
export function automaticModelIconId(model: IconModel): string {
  const id = model.modelId.trim(), shortId = id.slice(id.lastIndexOf('/') + 1)
  const deferred = new RegExp(catalog.deferredModelPattern, 'i')
  if (deferred.test(id) || deferred.test(shortId)) return (catalog.fallbacks as Record<string, string>)[model.serviceType] || catalog.fallbacks.unknown
  const match = rules.find(rule => rule.model.test(id) || rule.model.test(shortId))
    || rules.find(rule => rule.brand.test(model.displayName))
    || model.vendors.map(vendor => rules.find(rule => rule.brand.test(vendor))).find(Boolean)
  return match?.id || (catalog.fallbacks as Record<string, string>)[model.serviceType] || catalog.fallbacks.unknown
}
export function modelIconId(model: IconModel): string {
  return modelIcons.some(icon => icon.id === model.iconId) ? model.iconId! : automaticModelIconId(model)
}
export function modelIconUrl(id: string): string {
  const icon = modelIcons.find(item => item.id === id) || modelIcons.find(item => item.id === catalog.fallbacks.unknown)!
  return assets[`../../../src/main/resources/static/model-icons/${icon.file}`]
}
export function modelIconLabel(id: string): string {
  return modelIcons.find(icon => icon.id === id)?.label || '通用模型'
}
