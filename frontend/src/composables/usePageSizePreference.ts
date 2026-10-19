import { computed, ref, unref, watch, type MaybeRef } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { normalizePageSize, paginationPreferenceKey, readPageSizePreference, savePageSizePreference } from '@/utils/pagination'

/** Restore before the parent makes its first request; persistence never triggers a reload. */
export function usePageSizePreference(list: MaybeRef<string>) {
  const route = useRoute(), user = useUserStore()
  const key = computed(() => paginationPreferenceKey(user.userInfo?.account || 'anonymous',
    user.hasRole('ADMIN') ? 'admin' : 'user', route.path, unref(list)))
  const size = ref(readPageSizePreference(key.value))
  watch(key, next => { size.value = readPageSizePreference(next) }, { flush: 'sync' })
  watch(size, (value, previous) => {
    const normalized = normalizePageSize(value, normalizePageSize(previous))
    if (value !== normalized) { size.value = normalized; return }
    savePageSizePreference(key.value, value)
  }, { flush: 'sync' })
  return size
}
