import type { ModelCard, ModelDetail, PriceLine } from '@/api/modelSquare'
export const serviceLabels: Record<string, string> = { chat: '对话', embedding: '向量生成', rerank: '重排序', tts: '语音合成', stt: '语音识别', imgGen: '图像生成', imgEdit: '图像编辑', vidGen: '视频生成' }
export function formatPrice(value: number | null): string {
  if (value === null || !Number.isFinite(Number(value))) return '暂不可确认'
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 12, useGrouping: false }).format(Number(value))
}
export function priceText(row: PriceLine): string {
  if (row.status === 'NOT_CHARGED') return '不计费'
  if (row.status === 'IN_OUTPUT') return '并入输出'
  if (row.status === 'UNKNOWN') return '未配置'
  return `¥ ${formatPrice(row.effectivePrice)}`
}
export function filterModels(models: ModelCard[], search: string, service: string, vendor: string): ModelCard[] {
  const q = search.trim().toLocaleLowerCase()
  return models.filter(m => (!q || `${m.displayName} ${m.modelId}`.toLocaleLowerCase().includes(q))
    && (!service || m.serviceType === service) && (!vendor || m.vendors.includes(vendor)))
    .sort((a, b) => a.displayName.localeCompare(b.displayName, 'zh-CN') || a.modelId.localeCompare(b.modelId) || a.serviceType.localeCompare(b.serviceType))
}
const quote = (s: string) => "'" + s.replace(/'/g, "'\"'\"'") + "'"
export function connectionExample(detail: ModelDetail, origin: string): string {
  const id = detail.model.modelId
  const parts = [`curl -X ${detail.access.method} ${quote(origin + detail.access.path)}`, "  -H 'Authorization: Bearer YOUR_KEY'"]
  if (detail.model.serviceType === 'stt') parts.push(`  -F ${quote('model=' + id)}`, "  -F 'file=@YOUR_AUDIO_FILE.wav'")
  else if (detail.model.serviceType === 'imgEdit') parts.push(`  -F ${quote('model=' + id)}`, "  -F 'image=@YOUR_IMAGE_FILE.png'", "  -F 'prompt=请编辑这张图片'")
  else {
    const bodies: Record<string, object> = {
      chat: { model: id, messages: [{ role: 'user', content: '你好' }], stream: false },
      embedding: { model: id, input: ['需要生成向量的文本'] },
      rerank: { model: id, query: '查询文本', documents: ['文档一', '文档二'], top_n: 2 },
      tts: { model: id, input: '你好，欢迎使用算力平台', voice: 'YOUR_VOICE', response_format: 'mp3' },
      imgGen: { model: id, prompt: '一幅清爽的校园风景画', n: 1 },
      vidGen: { model: id, content: [{ type: 'text', text: '清晨的校园，镜头缓慢推进' }] }
    }
    parts.push("  -H 'Content-Type: application/json'", `  -d ${quote(JSON.stringify(bodies[detail.model.serviceType] || { model: id }))}`)
    if (detail.model.serviceType === 'tts') parts.push('  --output speech.mp3')
  }
  let result = parts.join(' \\\n')
  if (detail.access.queryPath) result += `\n\n# 将创建响应中的 id 替换为 TASK_ID；只查询自己创建的任务\ncurl ${quote(origin + detail.access.queryPath.replace('{taskId}', 'TASK_ID'))} \\\n  -H 'Authorization: Bearer YOUR_KEY'`
  return result
}
export async function copyText(text: string, clipboard: Pick<Clipboard, 'writeText'> | undefined = globalThis.navigator?.clipboard): Promise<boolean> {
  try { if (!clipboard) return false; await clipboard.writeText(text); return true } catch { return false }
}
