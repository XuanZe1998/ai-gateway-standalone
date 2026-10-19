<template>
  <nav class="app-pagination" :class="$attrs.class" :style="$attrs.style as any" aria-label="表格分页">
    <span class="pagination-summary" aria-live="polite">共 {{ total }} 条<span v-if="total"> · {{ start }}–{{ end }}</span></span>
    <div class="pagination-controls">
      <select v-if="canResize" :value="pageSize" :disabled="disabled" aria-label="每页条数" @change="resize">
        <option v-for="size in sizes" :key="size" :value="size">{{ size }} 条 / 页</option>
      </select>
      <span v-else class="pagination-size">{{ pageSize }} 条 / 页</span>
      <template v-if="canResize">
        <button v-if="!customizing" type="button" class="custom-size-button" :disabled="disabled" aria-label="自定义每页条数" @click="beginCustom">自定义</button>
        <div v-else class="custom-size-form">
          <label>每页 <input v-model="customSize" type="number" min="1" :max="MAX_PAGE_SIZE" step="1" :disabled="disabled" aria-label="自定义每页条数" @keydown.enter="applyCustom" @keydown.esc="customizing = false" /> 条</label>
          <button type="button" :disabled="disabled" @click="applyCustom">应用</button><button type="button" :disabled="disabled" @click="customizing = false">取消</button>
          <span v-if="customError" class="custom-size-error" role="alert">{{ customError }}</span>
        </div>
      </template>
      <button type="button" :disabled="disabled || current <= 1" aria-label="上一页" @click="change(current - 1)"><el-icon><ArrowLeft /></el-icon></button>
      <template v-for="item in items" :key="item">
        <button v-if="typeof item === 'number'" type="button" :class="{ active: item === current }" :aria-current="item === current ? 'page' : undefined" :aria-label="`第 ${item} 页`" :disabled="disabled" @click="change(item)">{{ item }}</button>
        <span v-else class="pagination-gap" aria-hidden="true">…</span>
      </template>
      <button type="button" :disabled="disabled || current >= count" aria-label="下一页" @click="change(current + 1)"><el-icon><ArrowRight /></el-icon></button>
      <label class="pagination-jump">前往 <input v-model="jump" type="number" min="1" :max="count" :disabled="disabled" aria-label="跳转页码" @keydown.enter="go" @change="go" /> 页</label>
    </div>
  </nav>
</template>

<script setup lang="ts">
import { computed, getCurrentInstance, ref, watch } from 'vue'
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import { clampPage, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE, PAGE_SIZES, pageCount, pageItems, validPageSize } from '@/utils/pagination'
defineOptions({ inheritAttrs: false })
const props = withDefaults(defineProps<{ total?: number; currentPage?: number; pageSize?: number; pageSizes?: number[]; disabled?: boolean }>(), {
  total: 0, currentPage: 1, pageSize: DEFAULT_PAGE_SIZE, pageSizes: () => PAGE_SIZES, disabled: false
})
const emit = defineEmits(['update:currentPage', 'update:pageSize', 'current-change', 'size-change'])
const instance = getCurrentInstance()
const canResize = computed(() => !!(instance?.vnode.props?.['onUpdate:pageSize'] || instance?.vnode.props?.onSizeChange))
const sizes = computed(() => [...new Set([DEFAULT_PAGE_SIZE, props.pageSize, ...props.pageSizes])].filter(validPageSize).sort((a, b) => a - b))
const customizing = ref(false), customSize = ref(''), customError = ref('')
const count = computed(() => pageCount(props.total, props.pageSize))
const current = computed(() => clampPage(props.currentPage, props.total, props.pageSize))
const start = computed(() => (current.value - 1) * props.pageSize + 1)
const end = computed(() => Math.min(current.value * props.pageSize, props.total))
const items = computed(() => pageItems(current.value, count.value))
const jump = ref(String(current.value))
watch(current, n => { jump.value = String(n) })
watch(() => props.total, () => {
  if (props.currentPage > count.value) change(count.value)
})
function change(n: number) {
  const next = clampPage(n, props.total, props.pageSize)
  jump.value = String(next)
  if (props.disabled || next === props.currentPage) return
  emit('update:currentPage', next)
  emit('current-change', next)
}
function go() { change(Number(jump.value)); jump.value = String(current.value) }
function resize(event: Event) {
  const size = Number((event.target as HTMLSelectElement).value)
  setSize(size)
}
function setSize(size: number) {
  if (props.disabled || !validPageSize(size) || size === props.pageSize) return
  emit('update:pageSize', size)
  emit('update:currentPage', 1)
  // Size handlers already reload data. Avoid issuing a second request for page 1.
  emit('size-change', size)
}
function beginCustom() { customSize.value = String(props.pageSize); customError.value = ''; customizing.value = true }
function applyCustom() {
  if (props.disabled) return
  if (!validPageSize(customSize.value)) { customError.value = `请输入 1～${MAX_PAGE_SIZE} 的整数`; return }
  setSize(Number(customSize.value))
  customError.value = ''; customizing.value = false
}
</script>

<style scoped>
.app-pagination{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;padding:18px 0 2px;color:var(--el-text-color-secondary);font-size:13px}
.pagination-controls{display:flex;align-items:center;gap:6px;flex-wrap:wrap}.pagination-summary{white-space:nowrap;font-variant-numeric:tabular-nums}
button,select,input{font:inherit;border:1px solid var(--el-border-color);border-radius:8px;background:var(--el-bg-color);color:var(--el-text-color-regular);height:32px}
button{min-width:32px;display:inline-flex;align-items:center;justify-content:center;cursor:pointer;padding:0 7px}button:hover:enabled{border-color:var(--el-color-primary);color:var(--el-color-primary)}button.active{background:var(--el-color-primary);color:white;border-color:var(--el-color-primary);font-weight:700}button:disabled{opacity:.4;cursor:not-allowed}
select{padding:0 8px;margin-right:8px}.pagination-size{margin-right:8px}.pagination-gap{padding:0 3px}.pagination-jump{display:flex;align-items:center;gap:6px;margin-left:8px}input{width:52px;text-align:center;padding:0 4px}button:focus-visible,select:focus-visible,input:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}
.custom-size-button{margin-right:6px;white-space:nowrap}.custom-size-form{display:flex;align-items:center;gap:6px;flex-wrap:wrap}.custom-size-form label{display:flex;align-items:center;gap:5px}.custom-size-form input{width:64px}.custom-size-error{color:var(--el-color-danger);font-size:12px}
@media(max-width:620px){.app-pagination{gap:12px}.pagination-jump{display:none}.pagination-controls{gap:4px}button{min-width:30px}}
</style>
