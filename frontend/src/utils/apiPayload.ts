/** Accept plain self-service payloads and the gateway's standard response envelope. */
export function apiPayload<T>(body: T | { success: boolean; data?: T; message?: string }): T {
  if (body && typeof body === 'object' && 'success' in body) {
    if (!body.success || body.data === undefined || body.data === null) {
      throw new Error(body.message || '请求失败')
    }
    return body.data
  }
  return body as T
}

export function issuedKeyPayload<T extends { secret: string }>(body: T | { success: boolean; data?: T; message?: string }): T {
  const issued = apiPayload(body)
  if (typeof issued?.secret !== 'string' || !issued.secret.startsWith('gw2_')) {
    throw new Error('服务器未返回有效密钥，请刷新 Key 列表后重试或轮换密钥')
  }
  return issued
}
