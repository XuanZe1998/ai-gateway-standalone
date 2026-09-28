<!-- 文件说明：ModelDiscoveryDialog：管理端页面或组件，维护交互与视图状态。 -->
<template>
  <el-dialog
    v-model="visible"
    title="发现上游模型"
    width="920px"
    :close-on-click-modal="false"
    destroy-on-close
  >
    <el-alert
      title="支持 OpenAI 兼容的 /v1/models 接口。基础 URL 可以填写服务根地址、/v1 地址或完整推理接口地址。"
      type="info"
      :closable="false"
      show-icon
      class="discovery-alert"
    />

    <el-form label-width="120px" class="discovery-form">
      <el-row :gutter="18">
        <el-col :span="12">
          <el-form-item label="服务类型" required>
            <el-select v-model="form.serviceType" style="width: 100%" @change="loadExistingModels">
              <el-option
                v-for="serviceType in serviceTypes"
                :key="serviceType"
                :label="serviceTypeLabels[serviceType] || serviceType"
                :value="serviceType"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="适配器">
            <el-select v-model="form.adapter" clearable placeholder="留空使用全局适配器" style="width: 100%">
              <el-option
                v-for="adapter in adapters"
                :key="adapter.name || adapter"
                :label="adapter.name || adapter"
                :value="adapter.name || adapter"
              />
            </el-select>
            <div v-if="!form.adapter && globalAdapter" class="form-note">使用全局适配器：{{ globalAdapter }}</div>
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="基础 URL" required>
        <el-input
          v-model="form.baseUrl"
          placeholder="例如 https://api.example.com/v1 或 .../v1/chat/completions"
          clearable
        />
      </el-form-item>

      <el-form-item label="模型列表 URL">
        <el-input v-model="form.modelsUrl" placeholder="可选；留空时自动推导 /v1/models" clearable />
      </el-form-item>

      <el-row :gutter="18">
        <el-col :span="12">
          <el-form-item label="实例路径">
            <el-input v-model="form.path" placeholder="批量导入实例时使用，可留空" />
          </el-form-item>
        </el-col>
        <el-col :span="6">
          <el-form-item label="权重">
            <el-input-number v-model="form.weight" :min="1" :max="100" />
          </el-form-item>
        </el-col>
        <el-col :span="6">
          <el-form-item label="状态">
            <el-switch
              v-model="form.status"
              active-value="active"
              inactive-value="inactive"
              active-text="启用"
              inactive-text="禁用"
            />
          </el-form-item>
        </el-col>
      </el-row>

      <el-divider content-position="left">上游请求头</el-divider>
      <div class="headers-toolbar">
        <el-button size="small" @click="addHeader">
          <el-icon><Plus /></el-icon>
          添加请求头
        </el-button>
        <el-button size="small" type="success" plain @click="addAuthorizationHeader">添加 Authorization</el-button>
      </div>
      <div v-if="headers.length" class="headers-list">
        <el-row v-for="(header, index) in headers" :key="index" :gutter="10" class="header-row">
          <el-col :span="9">
            <el-input v-model="header.key" placeholder="请求头名称" />
          </el-col>
          <el-col :span="13">
            <el-input
              v-model="header.value"
              :type="isSensitiveHeader(header.key) ? 'password' : 'text'"
              :show-password="isSensitiveHeader(header.key)"
              placeholder="请求头值"
            />
          </el-col>
          <el-col :span="2">
            <el-button type="danger" plain circle @click="removeHeader(index)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </el-col>
        </el-row>
      </div>

      <div class="discover-action">
        <el-button type="primary" :loading="discovering" @click="handleDiscover">
          <el-icon><Search /></el-icon>
          {{ discovering ? '正在发现...' : '发现模型' }}
        </el-button>
        <span v-if="result.modelsUrl" class="models-url">实际请求：{{ result.modelsUrl }}</span>
      </div>
    </el-form>

    <template v-if="result.models.length">
      <el-divider content-position="left">发现结果（{{ result.models.length }} 个）</el-divider>
      <div class="result-toolbar">
        <el-input v-model="modelSearch" placeholder="搜索模型" clearable class="model-search">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <span>已选择 {{ selectedModels.length }} 个</span>
      </div>

      <el-table
        ref="modelTableRef"
        :data="filteredModels"
        row-key="id"
        max-height="360"
        border
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="48" :selectable="isModelSelectable" reserve-selection />
        <el-table-column prop="id" label="模型 ID" min-width="320" show-overflow-tooltip />
        <el-table-column prop="ownedBy" label="提供方" min-width="150">
          <template #default="scope">{{ scope.row.ownedBy || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="scope">
            <el-tag v-if="existingModelNames.has(scope.row.id.toLowerCase())" type="info">已存在</el-tag>
            <el-tag v-else type="success">可导入</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button
        type="primary"
        :loading="importing"
        :disabled="selectedModels.length === 0"
        @click="handleImport"
      >
        导入选中的 {{ selectedModels.length }} 个模型
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { Delete, Plus, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  discoverModels,
  getServiceInstances,
  importDiscoveredModels,
  type DiscoveredModel
} from '@/api/instance'

interface DiscoverySeed {
  serviceType?: string
  baseUrl?: string
  path?: string
  adapter?: string
  headers?: Record<string, string>
}

interface HeaderItem {
  key: string
  value: string
}

const props = defineProps<{
  modelValue: boolean
  serviceTypes: string[]
  serviceTypeLabels: Record<string, string>
  adapters: any[]
  globalAdapter?: string
  seed?: DiscoverySeed
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'imported', serviceType: string): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value)
})

const form = reactive({
  serviceType: 'chat',
  baseUrl: '',
  modelsUrl: '',
  path: '',
  weight: 1,
  status: 'active' as 'active' | 'inactive',
  adapter: ''
})
const headers = ref<HeaderItem[]>([])
const discovering = ref(false)
const importing = ref(false)
const modelSearch = ref('')
const selectedModels = ref<DiscoveredModel[]>([])
const existingModelNames = ref(new Set<string>())
const modelTableRef = ref()
const result = reactive({ modelsUrl: '', models: [] as DiscoveredModel[] })

const filteredModels = computed(() => {
  const query = modelSearch.value.trim().toLowerCase()
  if (!query) return result.models
  return result.models.filter(model =>
    model.id.toLowerCase().includes(query) || (model.ownedBy || '').toLowerCase().includes(query)
  )
})

watch(() => props.modelValue, async isOpen => {
  if (!isOpen) return
  form.serviceType = props.seed?.serviceType || props.serviceTypes[0] || 'chat'
  form.baseUrl = props.seed?.baseUrl || ''
  form.modelsUrl = ''
  form.path = props.seed?.path || ''
  form.weight = 1
  form.status = 'active'
  form.adapter = props.seed?.adapter || ''
  headers.value = Object.entries(props.seed?.headers || {}).map(([key, value]) => ({ key, value }))
  result.modelsUrl = ''
  result.models = []
  modelSearch.value = ''
  selectedModels.value = []
  await loadExistingModels()
})

const buildHeaders = (): Record<string, string> => {
  const values: Record<string, string> = {}
  headers.value.forEach(header => {
    const key = header.key.trim()
    if (key) values[key] = header.value
  })
  return values
}

const addHeader = () => headers.value.push({ key: '', value: '' })

const addAuthorizationHeader = () => {
  if (headers.value.some(header => header.key.toLowerCase() === 'authorization')) {
    ElMessage.info('Authorization 请求头已存在')
    return
  }
  headers.value.push({ key: 'Authorization', value: 'Bearer ' })
}

const removeHeader = (index: number) => headers.value.splice(index, 1)

const isSensitiveHeader = (name: string) => {
  const normalized = name.toLowerCase()
  return normalized.includes('authorization') || normalized.includes('api-key') || normalized.includes('apikey')
}

const loadExistingModels = async () => {
  try {
    const response = await getServiceInstances(form.serviceType)
    const names = (response.data?.data || [])
      .map(instance => String(instance.name || '').toLowerCase())
      .filter(Boolean)
    existingModelNames.value = new Set(names)
  } catch {
    existingModelNames.value = new Set()
  }
}

const isModelSelectable = (model: DiscoveredModel) => {
  return !existingModelNames.value.has(model.id.toLowerCase())
}

const handleDiscover = async () => {
  if (!form.baseUrl.trim()) {
    ElMessage.warning('请输入基础 URL')
    return
  }

  discovering.value = true
  try {
    await loadExistingModels()
    const response = await discoverModels({
      baseUrl: form.baseUrl.trim(),
      modelsUrl: form.modelsUrl.trim() || undefined,
      headers: buildHeaders()
    })
    if (!response.data?.success || !response.data.data) {
      ElMessage.error(response.data?.message || '发现模型失败')
      return
    }

    result.modelsUrl = response.data.data.modelsUrl
    result.models = response.data.data.models || []
    selectedModels.value = []
    await nextTick()
    modelTableRef.value?.clearSelection()
    result.models.forEach(model => {
      if (isModelSelectable(model)) modelTableRef.value?.toggleRowSelection(model, true)
    })
    ElMessage.success(`发现 ${result.models.length} 个模型`)
  } catch (error: any) {
    console.error('发现模型失败:', error)
    ElMessage.error(error?.response?.data?.message || error?.message || '发现模型失败')
  } finally {
    discovering.value = false
  }
}

const handleSelectionChange = (models: DiscoveredModel[]) => {
  selectedModels.value = models
}

const handleImport = async () => {
  if (!selectedModels.value.length) {
    ElMessage.warning('请选择要导入的模型')
    return
  }

  importing.value = true
  try {
    const response = await importDiscoveredModels(form.serviceType, {
      modelIds: selectedModels.value.map(model => model.id),
      baseUrl: form.baseUrl.trim(),
      path: form.path.trim(),
      weight: form.weight,
      status: form.status,
      adapter: form.adapter || undefined,
      headers: buildHeaders()
    })
    if (!response.data?.success || !response.data.data) {
      ElMessage.error(response.data?.message || '导入模型失败')
      return
    }

    const data = response.data.data
    ElMessage.success(`导入完成：新增 ${data.importedCount} 个，跳过 ${data.skippedCount} 个`)
    emit('imported', form.serviceType)
    visible.value = false
  } catch (error: any) {
    console.error('导入模型失败:', error)
    ElMessage.error(error?.response?.data?.message || error?.message || '导入模型失败')
  } finally {
    importing.value = false
  }
}
</script>

<style scoped>
.discovery-alert {
  margin-bottom: 18px;
}

.discovery-form {
  padding-right: 10px;
}

.form-note {
  width: 100%;
  color: #909399;
  font-size: 12px;
  margin-top: 4px;
}

.headers-toolbar,
.result-toolbar,
.discover-action {
  display: flex;
  align-items: center;
  gap: 12px;
}

.headers-list {
  margin: 12px 0 18px 120px;
  padding: 12px;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  background: #fafbfc;
}

.header-row + .header-row {
  margin-top: 10px;
}

.discover-action {
  justify-content: flex-start;
  margin-left: 120px;
}

.models-url {
  min-width: 0;
  color: #909399;
  font-size: 12px;
  word-break: break-all;
}

.result-toolbar {
  justify-content: space-between;
  margin-bottom: 12px;
  color: #606266;
}

.model-search {
  width: 320px;
}
</style>
