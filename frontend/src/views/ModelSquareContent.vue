<template>
  <section class="page-shell">
    <div class="page-heading"><div><p class="eyebrow">MODEL SQUARE · CONTENT</p><h1>模型广场内容</h1><p>维护对所有用户可见的展示名称、介绍与标签。实际模型 ID、可用状态和收费规则不在此修改。</p></div><el-button @click="$router.push('/models')">查看模型广场</el-button></div>
    <el-alert title="当前仍保留全员 ADMIN：所有校园管理员都能修改这些全局展示内容。" type="warning" :closable="false" show-icon class="gap" />
    <el-card class="content-panel">
      <div class="content-toolbar"><el-input v-model="search" clearable placeholder="搜索展示名称或模型 ID" aria-label="搜索广场内容" /><el-button :loading="loading" @click="load">刷新</el-button></div>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="gap"><template #default><el-button text @click="load">重试</el-button></template></el-alert>
      <el-table v-loading="loading" :data="paged" empty-text="暂无匹配的运行模型">
        <el-table-column label="模型" min-width="240"><template #default="{ row }"><strong>{{ row.model.displayName }}</strong><p class="table-id">{{ row.model.modelId }}</p></template></el-table-column>
        <el-table-column label="服务类型" min-width="120"><template #default="{ row }">{{ serviceLabels[row.model.serviceType] }}</template></el-table-column>
        <el-table-column label="内容来源" min-width="120"><template #default="{ row }"><el-tag :type="row.override ? 'primary' : 'info'" effect="plain">{{ row.override ? '自定义内容' : '平台默认' }}</el-tag></template></el-table-column>
        <el-table-column label="最后更新" min-width="170"><template #default="{ row }">{{ row.updatedAt ? row.updatedAt.replace('T', ' ').slice(0, 19) : '—' }}</template></el-table-column>
        <el-table-column prop="updatedBy" label="操作人" min-width="120" />
        <el-table-column label="操作" min-width="180"><template #default="{ row }"><el-button link type="primary" @click="edit(row)">编辑内容</el-button><el-button link type="danger" :disabled="!row.override || resetting" @click="reset(row)">恢复默认</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-if="filtered.length > 12" v-model:current-page="page" :page-size="12" :total="filtered.length" layout="prev, pager, next" class="content-pagination" />
    </el-card>
    <el-dialog v-model="dialog" title="编辑模型展示内容" :width="smallScreen ? '94%' : '680px'" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
      <template v-if="selected">
        <el-alert title="保存后对所有用户生效，不影响真实模型 ID 或计费配置。空字段沿用平台默认内容。" type="info" :closable="false" show-icon />
        <p class="editing-id">{{ serviceLabels[selected.model.serviceType] }} · {{ selected.model.modelId }}</p>
        <el-form label-position="top">
          <el-form-item label="展示名称"><el-input v-model="form.displayName" :maxlength="100" show-word-limit :placeholder="selected.defaultName" :disabled="saving" /></el-form-item>
          <el-form-item label="模型简介"><el-input v-model="form.description" type="textarea" :rows="7" :maxlength="2000" show-word-limit :placeholder="selected.defaultDescription" :disabled="saving" /></el-form-item>
          <el-form-item label="能力 / 用途标签（最多 6 个，每个不超过 16 字）"><el-select v-model="form.tags" multiple filterable allow-create default-first-option :multiple-limit="6" placeholder="输入标签后按回车；留空沿用真实能力标签" style="width:100%" :disabled="saving"><el-option v-for="tag in selected.defaultTags" :key="tag" :label="tag" :value="tag" /></el-select></el-form-item>
        </el-form>
        <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" />
      </template>
      <template #footer><el-button :disabled="saving" @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存全局内容</el-button></template>
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getModelContent, resetModelContent, saveModelContent, type ContentView } from '@/api/modelSquare'
import { serviceLabels } from '@/utils/modelSquare'
const items = ref<ContentView[]>([]), loading = ref(false), error = ref(''), search = ref(''), page = ref(1)
const filtered = computed(() => items.value.filter(v => `${v.model.displayName} ${v.model.modelId}`.toLocaleLowerCase().includes(search.value.trim().toLocaleLowerCase())))
const paged = computed(() => filtered.value.slice((page.value - 1) * 12, page.value * 12))
watch(search, () => { page.value = 1 })
watch(() => filtered.value.length, n => { page.value = Math.min(page.value, Math.max(1, Math.ceil(n / 12))) })
const dialog = ref(false), selected = ref<ContentView | null>(null), saving = ref(false), resetting = ref(false), saveError = ref('')
const form = reactive({ displayName: '', description: '', tags: [] as string[] })
const media = window.matchMedia('(max-width: 600px)'), smallScreen = ref(media.matches)
const resize = () => { smallScreen.value = media.matches }
async function load() { loading.value = true; error.value = ''; try { items.value = await getModelContent() } catch { error.value = '加载模型广场内容失败，请重试或重新登录。' } finally { loading.value = false } }
function edit(row: ContentView) { selected.value = row; form.displayName = row.override?.displayName || ''; form.description = row.override?.description || ''; form.tags = [...(row.override?.tags || [])]; saveError.value = ''; dialog.value = true }
async function save() {
  if (!selected.value || saving.value) return
  if (form.tags.length > 6 || form.tags.some(t => !t.trim() || [...t.trim()].length > 16)) { saveError.value = '标签最多 6 个，每个 1～16 字'; return }
  try { await ElMessageBox.confirm('保存后将修改所有用户看到的模型介绍，是否确认保存？', '保存全局内容', { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '取消' }) } catch { return }
  saving.value = true; saveError.value = ''
  try { await saveModelContent({ serviceType: selected.value.model.serviceType, modelId: selected.value.model.modelId, ...form, tags: [...form.tags] }); ElMessage.success('内容已保存'); dialog.value = false; await load() }
  catch { saveError.value = '保存失败，请检查长度限制、登录状态及模型是否仍在运行目录中。' } finally { saving.value = false }
}
async function reset(row: ContentView) {
  try { await ElMessageBox.confirm('这将移除自定义展示内容，恢复平台默认名称、简介和能力标签，对所有用户生效。', '恢复平台默认', { type: 'warning', confirmButtonText: '确认恢复', cancelButtonText: '取消' }) } catch { return }
  resetting.value = true
  try { await resetModelContent(row.model.serviceType, row.model.modelId); ElMessage.success('已恢复默认'); await load() } catch { ElMessage.error('恢复失败，请重试') } finally { resetting.value = false }
}
onMounted(() => { media.addEventListener('change', resize); load() })
onUnmounted(() => media.removeEventListener('change', resize))
</script>
<style scoped>
.content-toolbar{display:flex;gap:12px;margin-bottom:20px}.content-toolbar .el-input{max-width:420px}.table-id{font:11px/1.7 ui-monospace,Consolas,monospace;color:#8296a7;overflow-wrap:anywhere;margin:5px 0}.content-pagination{justify-content:center;margin-top:22px}.editing-id{font-size:12px;overflow-wrap:anywhere;color:#7c93a4;line-height:1.8;margin:20px 0}.content-panel{border-radius:16px}
@media(max-width:600px){.page-heading{align-items:flex-start;gap:12px}.content-toolbar .el-input{max-width:none}}
</style>
