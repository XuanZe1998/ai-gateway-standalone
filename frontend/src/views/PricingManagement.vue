<template>
  <section class="page-shell">
    <div class="page-heading">
      <div>
        <p class="eyebrow">MODEL PRICING</p>
        <h1>模型定价管理</h1>
        <p>维护各模型的单价与计费规则，保存后立即生效，模型广场与计费链路同步更新。视频模型暂不支持。</p>
      </div>
      <el-tooltip content="Excel 批量导入功能开发中" placement="top">
        <span><el-button disabled><el-icon><Upload /></el-icon>&nbsp;导入 Excel</el-button></span>
      </el-tooltip>
    </div>

    <el-card class="panel">
      <div class="toolbar">
        <el-input v-model="search" clearable placeholder="搜索模型 ID" class="search" />
        <el-select v-model="service" clearable placeholder="全部服务类型" class="service-select">
          <el-option v-for="s in serviceTypes" :key="s" :label="serviceTags[s] || s" :value="s" />
        </el-select>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="gap" />
      <el-table v-loading="loading" :data="paged" empty-text="暂无运行中的模型，请先在实例管理中导入模型">
        <el-table-column label="模型 ID" min-width="260">
          <template #default="{ row }">
            <strong>{{ row.modelId }}</strong>
            <p class="sub">{{ serviceTags[row.serviceType] || row.serviceType }}<template v-if="row.vendor"> · {{ row.vendor }}</template></p>
          </template>
        </el-table-column>
        <el-table-column label="定价状态" min-width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="row.configured ? (row.billingMode === 2 ? 'warning' : 'primary') : 'info'" effect="plain">
              {{ row.configured ? (row.billingMode === 2 ? '阶梯计费' : '整体计费') : (row.billingMode === 2 ? '阶梯·缺档位' : '未配置') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="输入(元/M)" min-width="120" align="right">
          <template #default="{ row }">{{ fmt(row.inputPrice) }}</template>
        </el-table-column>
        <el-table-column label="输出(元/M)" min-width="120" align="right">
          <template #default="{ row }">{{ fmt(row.outputPrice) }}</template>
        </el-table-column>
        <el-table-column label="缓存命中价" min-width="110" align="right">
          <template #default="{ row }">{{ fmt(row.cacheHitInputPrice) }}</template>
        </el-table-column>
        <el-table-column label="档位" min-width="80" align="center">
          <template #default="{ row }">{{ row.tierCount > 0 ? row.tierCount : '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openPricing(row)">编辑定价</el-button>
            <el-button link type="warning" @click="openTiers(row)">阶梯档位</el-button>
            <el-button link type="danger" :disabled="!row.configured" @click="handleRemove(row)">清除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="filtered.length > 15"
        v-model:current-page="page"
        :page-size="15"
        :total="filtered.length"
        layout="total, prev, pager, next"
        class="pager"
      />
    </el-card>

    <!-- 编辑主定价 -->
    <el-dialog v-model="pricingVisible" title="编辑模型定价" width="780px" destroy-on-close :close-on-click-modal="false">
      <el-alert title="通过“+”添加计费维度，已添加维度需填写单价。未添加任何策略时模型将不可调用。" type="info" :closable="false" show-icon class="gap" />
      <el-form label-width="110px">
        <el-form-item label="模型">{{ serviceTags[form.serviceType] || form.serviceType }} · {{ form.modelId }}</el-form-item>
        <el-form-item label="厂商标识">
          <el-input v-model="form.vendor" placeholder="如 deepseek / openai / glm，用于用量归一与广场展示" clearable />
        </el-form-item>

        <el-form-item label="计费模式">
          <div class="mode-row">
            <el-radio-group v-model="form.billingMode">
              <el-radio-button :value="1">整体计费</el-radio-button>
              <el-radio-button :value="2">阶梯计费</el-radio-button>
            </el-radio-group>
            <el-popover v-model:visible="addStrategyVisible" placement="bottom-start" :width="300" trigger="click" :show-arrow="false">
              <div class="strategy-options">
                <div v-for="sk in availableStrategyKeys" :key="sk" class="strategy-option" @click="addStrategy(sk)">
                  <div class="strategy-opt-label">{{ PRICING_STRATEGIES[sk].label }}</div>
                  <div class="muted">{{ PRICING_STRATEGIES[sk].hint }}</div>
                </div>
                <div v-if="availableStrategyKeys.length === 0" class="muted strategy-empty">已添加全部可用策略</div>
              </div>
              <template #reference>
                <el-button circle size="small" type="primary" plain :disabled="availableStrategyKeys.length === 0">
                  <el-icon><Plus /></el-icon>
                </el-button>
              </template>
            </el-popover>
          </div>
        </el-form-item>

        <el-divider content-position="left">{{ groupLabel }}策略<span class="muted"> · 未添加的维度不计费</span></el-divider>

        <div v-if="form.strategies.length === 0" class="gap">
          <el-alert type="warning" :closable="false" show-icon title="尚未添加计费策略，保存后该模型将不可调用。" />
        </div>
        <div v-else class="strategy-list gap">
          <div v-for="sk in form.strategies" :key="sk" class="strategy-row">
            <template v-if="sk === 'discount'">
              <span class="strategy-label">{{ PRICING_STRATEGIES.discount.label }}</span>
              <el-input-number v-model="form.discount" :min="0" :max="100" :precision="0" controls-position="right" style="width:180px" placeholder="0-100" />
              <span class="muted">{{ PRICING_STRATEGIES.discount.hint }}</span>
              <el-button link type="danger" @click="removeStrategy('discount')">移除</el-button>
            </template>
            <template v-else-if="sk === 'thinking'">
              <span class="strategy-label">{{ PRICING_STRATEGIES.thinking.label }}</span>
              <el-select v-model="form.thinkingBillingMode" style="width:160px">
                <el-option :value="1" label="并入输出" />
                <el-option :value="2" label="单独计费" />
                <el-option :value="3" label="不计费" />
              </el-select>
              <el-input-number v-if="form.thinkingBillingMode === 2" v-model="form.thinkingPrice" :min="0" :precision="6" controls-position="right" style="width:180px" placeholder="思考单价" />
              <el-button link type="danger" @click="removeStrategy('thinking')">移除</el-button>
            </template>
            <template v-else>
              <span class="strategy-label">{{ PRICING_STRATEGIES[sk].label }}</span>
              <el-input-number
                :model-value="priceOf(sk)"
                @update:model-value="setPrice(sk, $event)"
                :min="0"
                :precision="6"
                controls-position="right"
                style="width:180px"
                placeholder="单价" />
              <span class="muted">{{ PRICING_STRATEGIES[sk].unit }}</span>
              <el-button link type="danger" @click="removeStrategy(sk)">移除</el-button>
            </template>
          </div>
        </div>

        <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" />
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="pricingVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSavePricing">保存定价</el-button>
      </template>
    </el-dialog>

    <!-- 阶梯档位 -->
    <el-dialog v-model="tiersVisible" title="阶梯档位（单位：K）" width="940px" destroy-on-close :close-on-click-modal="false">
      <el-alert :title="`模型：${tiersModel}。档位上下限单位为 K token（128 表示 128K）。保存前需已存在主定价记录，保存后计费模式自动切换为阶梯计费，并整体替换现有档位。`" type="info" :closable="false" show-icon class="gap" />
      <div class="tier-toolbar">
        <el-button size="small" type="primary" plain @click="addTier">添加档位</el-button>
        <span class="muted">按顺序保存；最后一档建议勾选“不限量”</span>
      </div>
      <el-table :data="tiers" size="small" class="gap">
        <el-table-column label="序号" width="60" align="center">
          <template #default="{ $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column label="下限(K)" min-width="120">
          <template #default="{ row }"><el-input-number v-model="row.lowerLimitK" :min="0" :precision="0" size="small" controls-position="right" style="width:100%" /></template>
        </el-table-column>
        <el-table-column label="上限(K)" min-width="120">
          <template #default="{ row }"><el-input-number v-model="row.upperLimitK" :min="0" :precision="0" size="small" controls-position="right" style="width:100%" :disabled="row.unlimited" /></template>
        </el-table-column>
        <el-table-column label="不限量" width="80" align="center">
          <template #default="{ row }"><el-checkbox v-model="row.unlimited" /></template>
        </el-table-column>
        <el-table-column label="输入价" min-width="120">
          <template #default="{ row }"><el-input-number v-model="row.inputPrice" :min="0" :precision="6" size="small" controls-position="right" style="width:100%" /></template>
        </el-table-column>
        <el-table-column label="缓存命中价" min-width="120">
          <template #default="{ row }"><el-input-number v-model="row.cacheHitInputPrice" :min="0" :precision="6" size="small" controls-position="right" style="width:100%" /></template>
        </el-table-column>
        <el-table-column label="输出价" min-width="120">
          <template #default="{ row }"><el-input-number v-model="row.outputPrice" :min="0" :precision="6" size="small" controls-position="right" style="width:100%" /></template>
        </el-table-column>
        <el-table-column label="操作" width="70" align="center">
          <template #default="{ $index }"><el-button link type="danger" @click="tiers.splice($index, 1)">删除</el-button></template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button :disabled="savingTiers" @click="tiersVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingTiers" @click="handleSaveTiers">保存阶梯</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Upload } from '@element-plus/icons-vue'
import {
  getPricingList,
  getPricingDetail,
  savePricing as savePricingRequest,
  savePricingTiers as saveTiersRequest,
  deletePricing as deletePricingRequest,
  PRICING_STRATEGIES,
  STRATEGY_GROUP,
  type PricingRow,
  type PricingTier,
  type PricingStrategyKey
} from '@/api/pricing'
import { getServiceTypeLabel } from '@/constants/serviceTypes'

const serviceTypes = ['chat', 'embedding', 'rerank', 'tts', 'stt', 'imgGen', 'imgEdit']
const serviceTags: Record<string, string> = Object.fromEntries(
  serviceTypes.map(s => [s, getServiceTypeLabel(s)])
)

const rows = ref<PricingRow[]>([])
const loading = ref(false)
const error = ref('')
const search = ref('')
const service = ref('')
const page = ref(1)

const filtered = computed(() => rows.value.filter(r => {
  const q = search.value.trim().toLowerCase()
  return (!q || r.modelId.toLowerCase().includes(q)) && (!service.value || r.serviceType === service.value)
}))
const paged = computed(() => filtered.value.slice((page.value - 1) * 15, page.value * 15))
watch([search, service], () => { page.value = 1 })

function fmt(v: number | null | undefined) { return v == null ? '—' : `¥ ${v}` }

async function load() {
  loading.value = true
  error.value = ''
  try { rows.value = await getPricingList() }
  catch { error.value = '加载定价列表失败，请重试或重新登录。' }
  finally { loading.value = false }
}

// ===== 编辑主定价（策略化）=====
const pricingVisible = ref(false)
const saving = ref(false)
const saveError = ref('')
const addStrategyVisible = ref(false)
const form = reactive({
  serviceType: '',
  modelId: '',
  vendor: '',
  billingMode: 1,
  inputPrice: null as number | null,
  cacheHitInputPrice: null as number | null,
  outputPrice: null as number | null,
  cacheCreateInputPrice: null as number | null,
  cacheHitExplicitInputPrice: null as number | null,
  enableInputToken: true,
  enableCacheHitInput: true,
  enableOutputToken: true,
  enableCacheCreateInput: false,
  enableCacheHitExplicitInput: false,
  thinkingBillingMode: 1,
  thinkingPrice: null as number | null,
  discount: null as number | null,
  strategies: [] as PricingStrategyKey[]
})

const groupLabel = computed(() => STRATEGY_GROUP[form.serviceType]?.label || '模型计费')
const availableStrategyKeys = computed(() =>
  (STRATEGY_GROUP[form.serviceType]?.keys || []).filter(k => !form.strategies.includes(k))
)

function addStrategy(sk: PricingStrategyKey) {
  if (!form.strategies.includes(sk)) form.strategies.push(sk)
  addStrategyVisible.value = false
}

function removeStrategy(sk: PricingStrategyKey) {
  form.strategies = form.strategies.filter(s => s !== sk)
  if (sk === 'normalPrice') { form.enableInputToken = false; form.inputPrice = null }
  if (sk === 'output') { form.enableOutputToken = false; form.outputPrice = null }
  if (sk === 'cacheHit') { form.enableCacheHitInput = false; form.cacheHitInputPrice = null }
  if (sk === 'cacheCreate') { form.enableCacheCreateInput = false; form.cacheCreateInputPrice = null }
  if (sk === 'cacheHitExplicit') { form.enableCacheHitExplicitInput = false; form.cacheHitExplicitInputPrice = null }
  if (sk === 'thinking') { form.thinkingPrice = null }
  if (sk === 'discount') { form.discount = null }
}

function priceOf(sk: PricingStrategyKey): number | null {
  switch (sk) {
    case 'normalPrice': return form.inputPrice
    case 'output': return form.outputPrice
    case 'cacheHit': return form.cacheHitInputPrice
    case 'cacheCreate': return form.cacheCreateInputPrice
    case 'cacheHitExplicit': return form.cacheHitExplicitInputPrice
    default: return null
  }
}

function setPrice(sk: PricingStrategyKey, v: number | undefined | null) {
  const val = v ?? null
  switch (sk) {
    case 'normalPrice': form.inputPrice = val; break
    case 'output': form.outputPrice = val; break
    case 'cacheHit': form.cacheHitInputPrice = val; break
    case 'cacheCreate': form.cacheCreateInputPrice = val; break
    case 'cacheHitExplicit': form.cacheHitExplicitInputPrice = val; break
  }
}

async function openPricing(row: PricingRow) {
  saveError.value = ''
  try {
    const detail = await getPricingDetail(row.serviceType, row.modelId)
    const e = detail.editable
    Object.assign(form, {
      serviceType: e.serviceType,
      modelId: e.modelId,
      vendor: e.vendor || '',
      billingMode: e.billingMode ?? 1,
      inputPrice: e.inputPrice,
      cacheHitInputPrice: e.cacheHitInputPrice,
      outputPrice: e.outputPrice,
      cacheCreateInputPrice: e.cacheCreateInputPrice,
      cacheHitExplicitInputPrice: e.cacheHitExplicitInputPrice,
      enableInputToken: e.enableInputToken ?? true,
      enableCacheHitInput: e.enableCacheHitInput ?? true,
      enableOutputToken: e.enableOutputToken ?? true,
      enableCacheCreateInput: e.enableCacheCreateInput ?? false,
      enableCacheHitExplicitInput: e.enableCacheHitExplicitInput ?? false,
      thinkingBillingMode: e.thinkingBillingMode ?? 1,
      thinkingPrice: e.thinkingPrice,
      discount: e.discount
    })
    // 根据已保存的 enable/价格反推已选策略
    const sk: PricingStrategyKey[] = []
    if (e.enableInputToken ?? true) sk.push('normalPrice')
    if (e.enableOutputToken ?? true) sk.push('output')
    if (e.enableCacheHitInput ?? true) sk.push('cacheHit')
    if (e.enableCacheCreateInput ?? false) sk.push('cacheCreate')
    if (e.enableCacheHitExplicitInput ?? false) sk.push('cacheHitExplicit')
    if ((e.thinkingBillingMode ?? 1) !== 1 || e.thinkingPrice != null) sk.push('thinking')
    if (e.discount != null) sk.push('discount')
    form.strategies = sk.filter(k => STRATEGY_GROUP[form.serviceType]?.keys.includes(k))
  } catch {
    Object.assign(form, {
      serviceType: row.serviceType,
      modelId: row.modelId,
      vendor: row.vendor || '',
      billingMode: row.billingMode ?? 1,
      inputPrice: row.inputPrice,
      cacheHitInputPrice: row.cacheHitInputPrice,
      outputPrice: row.outputPrice,
      cacheCreateInputPrice: null,
      cacheHitExplicitInputPrice: null,
      enableInputToken: true,
      enableCacheHitInput: true,
      enableOutputToken: true,
      enableCacheCreateInput: false,
      enableCacheHitExplicitInput: false,
      thinkingBillingMode: 1,
      thinkingPrice: row.thinkingPrice,
      discount: null
    })
    const sk: PricingStrategyKey[] = []
    if (row.inputPrice != null) sk.push('normalPrice')
    if (row.outputPrice != null) sk.push('output')
    if (row.cacheHitInputPrice != null) sk.push('cacheHit')
    if (row.thinkingPrice != null) sk.push('thinking')
    form.strategies = sk.filter(k => STRATEGY_GROUP[form.serviceType]?.keys.includes(k))
  }
  pricingVisible.value = true
}

function validateStrategies(): boolean {
  saveError.value = ''
  if (form.strategies.length === 0) {
    saveError.value = '请至少添加一项计费策略；不添加任何策略保存后模型将不可调用。'
    return false
  }
  const priceOk = (k: PricingStrategyKey, v: number | null) => {
    if (form.strategies.includes(k) && (v == null || v < 0)) {
      saveError.value = `${PRICING_STRATEGIES[k].label}的价格必填且不能为负`
      return false
    }
    return true
  }
  if (!priceOk('normalPrice', form.inputPrice)) return false
  if (!priceOk('output', form.outputPrice)) return false
  if (!priceOk('cacheHit', form.cacheHitInputPrice)) return false
  if (!priceOk('cacheCreate', form.cacheCreateInputPrice)) return false
  if (!priceOk('cacheHitExplicit', form.cacheHitExplicitInputPrice)) return false
  if (form.strategies.includes('thinking')
      && form.thinkingBillingMode === 2
      && (form.thinkingPrice == null || form.thinkingPrice < 0)) {
    saveError.value = '单独计费模式下思考单价必填且不能为负'
    return false
  }
  if (form.strategies.includes('discount')
      && (form.discount == null || form.discount < 0 || form.discount > 100)) {
    saveError.value = '折扣范围为 0-100'
    return false
  }
  return true
}

async function handleSavePricing() {
  if (saving.value) return
  if (!validateStrategies()) return
  try {
    await ElMessageBox.confirm(
      '保存后定价立即生效，影响模型广场展示与实际扣费，是否确认？',
      '保存定价', { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '取消' })
  } catch { return }
  saving.value = true
  saveError.value = ''
  try {
    const hasInput = form.strategies.includes('normalPrice')
    const hasOutput = form.strategies.includes('output')
    const hasCacheHit = form.strategies.includes('cacheHit')
    const hasCacheCreate = form.strategies.includes('cacheCreate')
    const hasCacheHitExplicit = form.strategies.includes('cacheHitExplicit')
    const hasThinking = form.strategies.includes('thinking')
    const hasDiscount = form.strategies.includes('discount')
    const payload = {
      serviceType: form.serviceType,
      modelId: form.modelId,
      vendor: form.vendor.trim() || null,
      billingMode: form.billingMode,
      inputPrice: hasInput ? form.inputPrice : null,
      cacheHitInputPrice: hasCacheHit ? form.cacheHitInputPrice : null,
      outputPrice: hasOutput ? form.outputPrice : null,
      cacheCreateInputPrice: hasCacheCreate ? form.cacheCreateInputPrice : null,
      cacheHitExplicitInputPrice: hasCacheHitExplicit ? form.cacheHitExplicitInputPrice : null,
      enableInputToken: hasInput,
      enableCacheHitInput: hasCacheHit,
      enableOutputToken: hasOutput,
      enableCacheCreateInput: hasCacheCreate,
      enableCacheHitExplicitInput: hasCacheHitExplicit,
      thinkingBillingMode: hasThinking ? form.thinkingBillingMode : 1,
      thinkingPrice: hasThinking && form.thinkingBillingMode === 2 ? form.thinkingPrice : null,
      discount: hasDiscount ? form.discount : null
    }
    await savePricingRequest(payload)
    ElMessage.success('定价已保存')
    pricingVisible.value = false
    await load()
  } catch (err: any) {
    saveError.value = err?.response?.data?.message || '保存失败，请检查输入或登录状态。'
  } finally { saving.value = false }
}

// ===== 阶梯档位 =====
const tiersVisible = ref(false)
const savingTiers = ref(false)
const tiers = ref<PricingTier[]>([])
const tiersModel = ref('')
const tierRow = ref<PricingRow | null>(null)

async function openTiers(row: PricingRow) {
  tierRow.value = row
  tiersModel.value = row.modelId
  tiersVisible.value = true
  try {
    const detail = await getPricingDetail(row.serviceType, row.modelId)
    tiers.value = detail.tiers.length ? detail.tiers.map(t => ({ ...t })) : [blankTier()]
  } catch {
    tiers.value = [blankTier()]
  }
}

function blankTier(): PricingTier {
  return {
    id: null, tierOrderNo: 0, lowerLimitK: 0, upperLimitK: null, unlimited: true,
    inputPrice: null, cacheHitInputPrice: null, outputPrice: null,
    cacheCreateInputPrice: null, cacheHitExplicitInputPrice: null, thinkingPrice: null
  }
}

function addTier() { tiers.value.push(blankTier()) }

async function handleSaveTiers() {
  const row = tierRow.value
  if (!row || savingTiers.value) return
  if (!tiers.value.length) { ElMessage.warning('至少保留一个档位'); return }
  try {
    await ElMessageBox.confirm('保存将整体替换该模型的阶梯档位，是否确认？', '保存阶梯', { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '取消' })
  } catch { return }
  savingTiers.value = true
  try {
    await saveTiersRequest(row.serviceType, row.modelId, tiers.value.map((t, i) => ({ ...t, tierOrderNo: i + 1 })))
    ElMessage.success('阶梯档位已保存')
    tiersVisible.value = false
    await load()
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.message || '保存阶梯失败，请先保存主定价再维护档位')
  } finally { savingTiers.value = false }
}

// ===== 清除定价 =====
async function handleRemove(row: PricingRow) {
  try {
    await ElMessageBox.confirm(`清除 ${row.modelId} 的定价配置？清除后该模型将按“未配置”处理。`, '清除定价', { type: 'error', confirmButtonText: '确认清除', cancelButtonText: '取消' })
  } catch { return }
  try {
    const ok = await deletePricingRequest(row.serviceType, row.modelId)
    if (ok) { ElMessage.success('已清除定价'); await load() }
    else { ElMessage.info('未找到该模型的定价记录') }
  } catch { ElMessage.error('清除失败，请重试') }
}

onMounted(load)
</script>

<style scoped>
.panel { border-radius: 16px }
.toolbar { display: flex; gap: 12px; margin-bottom: 18px }
.search { max-width: 320px }
.service-select { width: 190px }
.sub { margin: 4px 0 0; font-size: 12px; color: #8296a7 }
.gap { margin-bottom: 16px }
.tier-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px }
.muted { font-size: 12px; color: #8296a7 }
.pager { justify-content: flex-end; margin-top: 16px }
.mode-row { display: flex; align-items: center; gap: 12px }
.strategy-list { display: flex; flex-direction: column; gap: 10px }
.strategy-row { display: flex; align-items: center; gap: 12px; padding: 10px 12px; background: #f7fafc; border: 1px solid #e4edf3; border-radius: 8px }
.strategy-label { min-width: 150px; font-weight: 600 }
.strategy-options { display: flex; flex-direction: column; gap: 4px }
.strategy-option { padding: 8px 10px; border-radius: 6px; cursor: pointer }
.strategy-option:hover { background: #ecf5ff }
.strategy-opt-label { font-weight: 600 }
.strategy-empty { padding: 8px 0 }
</style>