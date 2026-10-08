<template>
  <section class="page-shell">
    <div class="page-heading">
      <div>
        <p class="eyebrow">BILLING DIMENSIONS</p>
        <h1>计费维度管理</h1>
        <p>定义各计费组的维度键与显示名。维度键不可修改，显示名可自定义。规则计费引擎按维度键从 price_json 取价。</p>
      </div>
    </div>

    <el-card class="panel">
      <div class="toolbar">
        <el-radio-group v-model="activeGroup">
          <el-radio-button value="text">文本计费</el-radio-button>
          <el-radio-button value="voice">语音计费</el-radio-button>
          <el-radio-button value="image">图像计费</el-radio-button>
        </el-radio-group>
        <el-button type="primary" @click="openCreate" :disabled="!activeGroup"><el-icon><Plus /></el-icon>&nbsp;新增维度</el-button>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="filteredDims" empty-text="暂无维度">
        <el-table-column label="维度键" min-width="160" prop="dimensionKey" />
        <el-table-column label="显示名" min-width="200">
          <template #default="{ row }">
            <el-input v-if="editingId === row.id" v-model="editForm.displayName" maxlength="60" size="small" />
            <span v-else>{{ row.displayName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="单位" min-width="140">
          <template #default="{ row }">
            <el-input v-if="editingId === row.id" v-model="editForm.unit" size="small" style="width:120px" />
            <span v-else>{{ row.unit }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" min-width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.valueType === 'price' ? 'primary' : row.valueType === 'discount' ? 'warning' : 'info'" effect="plain" size="small">
              {{ row.valueType === 'price' ? '单价' : row.valueType === 'discount' ? '折扣' : '开关' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="排序" min-width="80" align="center">
          <template #default="{ row }">
            <el-input-number v-if="editingId === row.id" v-model="editForm.sortOrder" :min="0" :max="99" size="small" controls-position="right" style="width:80px" />
            <span v-else>{{ row.sortOrder }}</span>
          </template>
        </el-table-column>
        <el-table-column label="启用" min-width="70" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" @change="(v: any) => toggleEnabled(row, v)" :disabled="editingId === row.id" />
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="160" fixed="right">
          <template #default="{ row }">
            <template v-if="editingId === row.id">
              <el-button link type="primary" @click="saveEdit(row)">保存</el-button>
              <el-button link @click="cancelEdit">取消</el-button>
            </template>
            <template v-else>
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增维度弹窗 -->
    <el-dialog v-model="createVisible" title="新增计费维度" width="480px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="计费组">
          <el-tag>{{ groupLabel(activeGroup) }}</el-tag>
        </el-form-item>
        <el-form-item label="维度键">
          <el-input v-model="createForm.dimensionKey" placeholder="如 customInput / videoDuration" maxlength="32" />
          <div class="muted">英文标识，创建后不可修改</div>
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="createForm.displayName" placeholder="如 自定义输入" maxlength="60" />
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="createForm.unit" placeholder="如 元 / 百万 Token" maxlength="20" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="createForm.valueType" style="width:100%">
            <el-option value="price" label="单价（数值）" />
            <el-option value="flag" label="开关（布尔）" />
            <el-option value="discount" label="折扣（0-100）" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreate">创建</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { getDimensions, createDimension, updateDimension, deleteDimension, type BillingDimension } from '@/api/pricing'

const loading = ref(false)
const dims = ref<BillingDimension[]>([])
const activeGroup = ref('text')

const filteredDims = computed(() => dims.value.filter(d => d.groupKey === activeGroup.value))

function groupLabel(g: string) {
  return g === 'text' ? '文本计费' : g === 'voice' ? '语音计费' : g === 'image' ? '图像计费' : g
}

async function load() {
  loading.value = true
  try { dims.value = await getDimensions() }
  catch { ElMessage.error('加载维度列表失败') }
  finally { loading.value = false }
}

// 编辑
const editingId = ref<number | null>(null)
const editForm = reactive({ displayName: '', unit: '', sortOrder: 0 })

function openEdit(row: BillingDimension) {
  editingId.value = row.id
  editForm.displayName = row.displayName
  editForm.unit = row.unit
  editForm.sortOrder = row.sortOrder
}

function cancelEdit() { editingId.value = null }

async function saveEdit(row: BillingDimension) {
  if (!editForm.displayName.trim()) { ElMessage.warning('显示名不能为空'); return }
  try {
    await updateDimension(row.id, { displayName: editForm.displayName.trim(), unit: editForm.unit, sortOrder: editForm.sortOrder })
    ElMessage.success('已保存')
    editingId.value = null
    await load()
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.message || '保存失败')
  }
}

async function toggleEnabled(row: BillingDimension, v: any) {
  try { await updateDimension(row.id, { enabled: v }) }
  catch { ElMessage.error('操作失败'); row.enabled = !v }
}

async function handleDelete(row: BillingDimension) {
  try {
    await ElMessageBox.confirm(`删除维度「${row.displayName}」？已被规则引用的维度禁止删除。`, '删除维度', { type: 'error', confirmButtonText: '确认删除', cancelButtonText: '取消' })
  } catch { return }
  try {
    await deleteDimension(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.message || '删除失败（可能已被规则引用）')
  }
}

// 新增
const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ dimensionKey: '', displayName: '', unit: '元 / 百万 Token', valueType: 'price' })

function openCreate() {
  createForm.dimensionKey = ''
  createForm.displayName = ''
  createForm.unit = '元 / 百万 Token'
  createForm.valueType = 'price'
  createVisible.value = true
}

async function handleCreate() {
  if (!createForm.dimensionKey.trim()) { ElMessage.warning('维度键必填'); return }
  if (!createForm.displayName.trim()) { ElMessage.warning('显示名必填'); return }
  creating.value = true
  try {
    await createDimension({
      groupKey: activeGroup.value,
      dimensionKey: createForm.dimensionKey.trim(),
      displayName: createForm.displayName.trim(),
      unit: createForm.unit,
      valueType: createForm.valueType
    })
    ElMessage.success('已创建')
    createVisible.value = false
    await load()
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.message || '创建失败')
  } finally { creating.value = false }
}

onMounted(load)
</script>

<style scoped>
.panel { border-radius: 16px }
.toolbar { display: flex; gap: 12px; margin-bottom: 18px; align-items: center }
.muted { font-size: 12px; color: #8296a7; margin-top: 4px }
</style>
