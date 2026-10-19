<template>
  <div class="app-table">
    <el-table ref="table" v-bind="$attrs" :data="visibleRows" @sort-change="sortChanged">
      <template v-for="(_, name) in $slots" #[name]="scope"><slot :name="name" v-bind="scope || {}" /></template>
    </el-table>
    <AppPagination v-if="paginated" v-model:current-page="page" v-model:page-size="size" :total="data.length" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, useAttrs, watch } from 'vue'
import type { TableInstance } from 'element-plus'
import { clampPage } from '@/utils/pagination'
import { usePageSizePreference } from '@/composables/usePageSizePreference'
defineOptions({ inheritAttrs: false })
const props = withDefaults(defineProps<{ data?: any[]; paginated?: boolean; paginationKey?: string }>(), { data: () => [], paginated: true })
const attrs = useAttrs()
const table = ref<TableInstance>()
const page = ref(1), size = usePageSizePreference(computed(() => props.paginationKey || 'table'))
watch(size, () => { page.value = 1 })
const sort = ref<{ prop?: string; order?: string | null; column?: any }>((attrs['default-sort'] as any) || {})
const orderedRows = computed(() => {
  if (!props.paginated || !sort.value.prop || !sort.value.order) return props.data
  const { prop, order, column } = sort.value
  if (column?.sortable === 'custom') return props.data
  const value = (row: any) => prop!.split('.').reduce((v, key) => v?.[key], row)
  return [...props.data].sort((a, b) => {
    const left = value(a), right = value(b)
    const result = column?.sortMethod ? column.sortMethod(a, b) : left == null ? (right == null ? 0 : -1) : right == null ? 1 : typeof left === 'number' && typeof right === 'number' ? left - right : String(left).localeCompare(String(right), 'zh-CN', { numeric: true })
    return order === 'descending' ? -result : result
  })
})
const visibleRows = computed(() => props.paginated ? orderedRows.value.slice((page.value - 1) * size.value, page.value * size.value) : props.data)
watch(() => props.data, () => { page.value = 1 })
watch(() => props.data.length, () => { page.value = clampPage(page.value, props.data.length, size.value) })
function sortChanged(state: typeof sort.value) { sort.value = state; if (props.paginated) page.value = 1 }
defineExpose({
  clearSelection: () => table.value?.clearSelection(),
  toggleRowSelection: (...args: Parameters<TableInstance['toggleRowSelection']>) => table.value?.toggleRowSelection(...args),
  toggleRowExpansion: (...args: Parameters<TableInstance['toggleRowExpansion']>) => table.value?.toggleRowExpansion(...args),
  setCurrentRow: (...args: Parameters<TableInstance['setCurrentRow']>) => table.value?.setCurrentRow(...args),
  doLayout: () => table.value?.doLayout()
})
</script>

<style scoped>.app-table{width:100%;min-width:0}</style>
