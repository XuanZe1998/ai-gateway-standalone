export const DEFAULT_PAGE_SIZE = 10
export const PAGE_SIZES = [10, 20, 50, 100]
export const MAX_PAGE_SIZE = 100

export function validPageSize(value: unknown): boolean {
  if (typeof value !== 'number' && typeof value !== 'string') return false
  if (typeof value === 'string' && !value.trim()) return false
  const size = Number(value)
  return Number.isInteger(size) && size >= 1 && size <= MAX_PAGE_SIZE
}

export function normalizePageSize(value: unknown, fallback = DEFAULT_PAGE_SIZE): number {
  return validPageSize(value) ? Number(value) : fallback
}

export function paginationPreferenceKey(account: string, area: string, path: string, list: string): string {
  return 'jairouter.pagination.v1:' + JSON.stringify([account, area, path, list])
}

function browserStorage(): Pick<Storage, 'getItem' | 'setItem'> | undefined {
  try { return globalThis.localStorage } catch { return undefined }
}

export function readPageSizePreference(key: string, storage = browserStorage()): number {
  try { return normalizePageSize(storage?.getItem(key)) } catch { return DEFAULT_PAGE_SIZE }
}

export function savePageSizePreference(key: string, value: unknown, storage = browserStorage()): boolean {
  if (!validPageSize(value) || !storage) return false
  try { storage.setItem(key, String(Number(value))); return true } catch { return false }
}

export function pageCount(total: number, size = DEFAULT_PAGE_SIZE): number {
  return Math.max(1, Math.ceil(Math.max(0, total) / Math.max(1, size)))
}

export function clampPage(page: number, total: number, size = DEFAULT_PAGE_SIZE): number {
  return Math.min(pageCount(total, size), Math.max(1, Math.trunc(page) || 1))
}

export function pageItems(page: number, count: number): (number | string)[] {
  const visible = new Set([1, count, page - 1, page, page + 1])
  if (page <= 3) [2, 3, 4].forEach(n => visible.add(n))
  if (page >= count - 2) [count - 3, count - 2, count - 1].forEach(n => visible.add(n))
  const pages = [...visible].filter(n => n >= 1 && n <= count).sort((a, b) => a - b)
  const result: (number | string)[] = []
  pages.forEach((n, i) => {
    const previous = pages[i - 1]
    if (previous != null && n - previous === 2) result.push(previous + 1)
    else if (previous != null && n - previous > 2) result.push(`gap-${previous}`)
    result.push(n)
  })
  return result
}
