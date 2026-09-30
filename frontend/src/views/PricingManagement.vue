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
            <el-tag :type="row.configured ? (row.billingMode === 2 ? 'warning' : row.billingMode === 3 ? 'success' : 'primary') : 'info'" effect="plain">
              {{ row.configured ? (row.billingMode === 2 ? '阶梯计费' : row.billingMode === 3 ? '规则计费' : '整体计费') : (row.billingMode === 2 ? '阶梯·档价未齐' : row.billingMode === 3 ? '规则·未配置' : '未配置') }}
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
            <el-button link type="success" @click="openRules(row)">规则计费</el-button>
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
    <el-dialog v-model="pricingVisible" title="编辑模型定价" width="860px" destroy-on-close :close-on-click-modal="false">
      <el-alert title="通过“+”添加计费维度，已添加维度需填写单价；未添加任何策略保存后模型将不可调用。维度名称可原位编辑，对同类计费模型全局生效，留空恢复默认名。" type="info" :closable="false" show-icon class="gap" />
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
              <el-radio-button :value="3">规则计费</el-radio-button>
            </el-radio-group>
            <el-tooltip content="阶梯计费下管理档位区间；各档单价在下方策略矩阵中填写" placement="top">
              <el-button size="small" type="warning" plain @click="openTiersInDialog">
                档位（{{ tierRanges.length }}）<el-icon class="btn-icon"><Setting /></el-icon>
              </el-button>
            </el-tooltip>
            <el-popover v-model:visible="addStrategyVisible" placement="bottom-start" :width="300" trigger="click" :show-arrow="false">
              <div class="strategy-options">
                <div v-for="sk in availableStrategyKeys" :key="sk" class="strategy-option" @click="addStrategy(sk)">
                  <div class="strategy-opt-label">{{ aliasLabel(sk, dimensionAliases) }}</div>
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

        <el-divider content-position="left">{{ groupLabel }}策略<span class="muted"> · 名称全局生效 · 二选一模式互不清空配置</span></el-divider>

        <div v-if="form.strategies.length === 0" class="gap">
          <el-alert type="warning" :closable="false" show-icon title="尚未添加计费策略，保存后该模型将不可调用。" />
        </div>
        <div v-else class="strategy-list gap">
          <div v-for="sk in form.strategies" :key="sk" class="strategy-row">
            <!-- 维度名称：原位编辑，留空=默认名（全局生效） -->
            <el-input
              v-model="dimensionAliases[sk]"
              :placeholder="PRICING_STRATEGIES[sk].label"
              maxlength="60"
              class="name-input">
              <template #prepend><el-icon><EditPen /></el-icon></template>
            </el-input>

            <!-- 折扣（模式无关） -->
            <template v-if="sk === 'discount'">
              <el-input-number v-model="form.discount" :min="0" :max="100" :precision="0" controls-position="right" style="width:170px" placeholder="0-100" />
              <span class="muted">{{ PRICING_STRATEGIES.discount.hint }}</span>
            </template>

            <!-- 思考 Token：模式选择 +（整体单价 / 阶梯矩阵） -->
            <template v-else-if="sk === 'thinking'">
              <el-select v-model="form.thinkingBillingMode" style="width:140px">
                <el-option :value="1" label="并入输出" />
                <el-option :value="2" label="单独计费" />
                <el-option :value="3" label="不计费" />
              </el-select>
              <template v-if="form.thinkingBillingMode === 2">
                <el-input-number
                  v-if="form.billingMode === 1"
                  v-model="form.thinkingPrice" :min="0" :precision="6" controls-position="right" style="width:170px" placeholder="思考单价" />
                <span v-else class="muted">元/M · 每档单价</span>
                <div v-if="form.billingMode === 2" class="tier-matrix">
                  <div v-for="(tr, i) in tierRanges" :key="i" class="tier-cell">
                    <div class="tier-head">档位{{ i + 1 }} · {{ rangeText(tr) }}</div>
                    <el-input-number v-model="tierPrices[i].thinkingPrice" :min="0" :precision="6" size="small" controls-position="right" class="tier-input" placeholder="单价" />
                  </div>
                  <div v-if="tierRanges.length === 0" class="tier-empty">暂无档位，请先在计费模式行「档位」中设置</div>
                </div>
              </template>
              <span v-else-if="form.thinkingBillingMode === 1" class="muted">并入输出，不单独计价</span>
              <span v-else class="muted">不计费</span>
            </template>

            <!-- 价格维度：整体=单价输入，阶梯=档位矩阵 -->
            <template v-else>
              <template v-if="form.billingMode === 1">
                <el-input-number
                  :model-value="priceOf(sk)"
                  @update:model-value="setPrice(sk, $event)"
                  :min="0"
                  :precision="6"
                  controls-position="right"
                  style="width:170px"
                  placeholder="单价" />
                <span class="muted">{{ PRICING_STRATEGIES[sk].unit }}</span>
              </template>
              <div v-else class="tier-matrix">
                <div v-for="(tr, i) in tierRanges" :key="i" class="tier-cell">
                  <div class="tier-head">档位{{ i + 1 }} · {{ rangeText(tr) }}</div>
                  <el-input-number
                    :model-value="tierPriceOf(i, sk)"
                    @update:model-value="setTierPrice(i, sk, $event)"
                    :min="0" :precision="6" size="small" controls-position="right" class="tier-input" placeholder="单价" />
                </div>
                <div v-if="tierRanges.length === 0" class="tier-empty">暂无档位，请先在计费模式行「档位」中设置</div>
              </div>
            </template>

            <el-button link type="danger" class="remove-btn" @click="removeStrategy(sk)">移除</el-button>
          </div>
        </div>

        <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" />
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="pricingVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSavePricing">保存定价</el-button>
      </template>
    </el-dialog>

    <!-- 阶梯档位（仅区间，价格在编辑定价的阶梯矩阵中维护） -->
    <el-dialog v-model="tiersVisible" title="阶梯档位（单位：K）" width="720px" destroy-on-close :close-on-click-modal="false" append-to-body>
      <el-alert :title="`模型：${tiersModel}。档位上下限单位为 K token（128 表示 128K），此处仅保存区间；各档单价请在「编辑定价」的阶梯计费矩阵中填写。保存后价格按序继承：超出丢弃、新增留空。`" type="info" :closable="false" show-icon class="gap" />
      <div class="tier-toolbar">
        <el-button size="small" type="primary" plain @click="addTier">添加档位</el-button>
        <span class="muted">按顺序保存；最后一档建议勾选“不限量”</span>
      </div>
      <el-table :data="tiers" size="small" class="gap">
        <el-table-column label="序号" width="60" align="center">
          <template #default="{ $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column label="下限(K)" min-width="150">
          <template #default="{ row }"><el-input-number v-model="row.lowerLimitK" :min="0" :precision="0" size="small" controls-position="right" style="width:100%" /></template>
        </el-table-column>
        <el-table-column label="上限(K)" min-width="150">
          <template #default="{ row }"><el-input-number v-model="row.upperLimitK" :min="0" :precision="0" size="small" controls-position="right" style="width:100%" :disabled="row.unlimited" /></template>
        </el-table-column>
        <el-table-column label="不限量" width="80" align="center">
          <template #default="{ row }"><el-checkbox v-model="row.unlimited" /></template>
        </el-table-column>
        <el-table-column label="操作" width="70" align="center">
          <template #default="{ $index }"><el-button link type="danger" @click="tiers.splice($index, 1)">删除</el-button></template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button :disabled="savingTiers" @click="tiersVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingTiers" @click="handleSaveTiers">保存档位</el-button>
      </template>
    </el-dialog>

    <!-- 规则计费管理 -->
    <el-dialog v-model="rulesVisible" title="规则计费管理" width="960px" destroy-on-close :close-on-click-modal="false" append-to-body>
      <el-alert :title="`模型：${rulesModelName}。按优先级匹配，第一条命中规则即为计费规则。切换到此模式后，整体/阶梯配置保留但生效。`" type="info" :closable="false" show-icon class="gap" />
      <div class="rule-toolbar">
        <el-button type="primary" plain size="small" @click="addRule"><el-icon><Plus /></el-icon>&nbsp;添加规则</el-button>
        <el-button v-if="rulesList.length > 1" type="warning" plain size="small" @click="handleSaveOrder">保存排序</el-button>
        <span class="muted">拖动行调整优先级后点「保存排序」</span>
      </div>
      <el-table :data="rulesList" class="gap" row-key="id" @row-click="toggleExpand">
        <el-table-column label="优先级" width="80" align="center">
          <template #default="{ row, $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column label="规则名称" min-width="160">
          <template #default="{ row }">{{ row.ruleName }}</template>
        </el-table-column>
        <el-table-column label="匹配条件" min-width="200">
          <template #default="{ row }">{{ formatMatch(row.matchJson) }}</template>
        </el-table-column>
        <el-table-column label="维度价格" min-width="180">
          <template #default="{ row }">{{ formatPrice(row.priceJson) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small" effect="plain">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="center">
          <template #default="{ row, $index }">
            <el-button link type="primary" @click.stop="editRule(row)">编辑</el-button>
            <el-button link type="danger" @click.stop="deleteRuleRow(row, $index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="rulesVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 规则编辑弹窗 -->
    <el-dialog v-model="ruleEditVisible" :title="ruleEditId ? '编辑规则' : '新增规则'" width="860px" destroy-on-close :close-on-click-modal="false" append-to-body>
      <RuleEditor v-if="ruleEditVisible" v-model="ruleEditData" :group-key="ruleEditGroupKey" />
      <template #footer>
        <el-button @click="ruleEditVisible = false">取消</el-button>
        <el-button type="primary" :loading="ruleSaving" @click="saveRule">保存规则</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { EditPen, Plus, Setting, Upload } from '@element-plus/icons-vue'
import RuleEditor from './RuleEditor.vue'
import {
  getPricingList,
  getPricingDetail,
  savePricing as savePricingRequest,
  savePricingTiers as saveTiersRequest,
  deletePricing as deletePricingRequest,
  getRules, createRule, updateRule, deleteRule, reorderRules,
  PRICING_STRATEGIES,
  STRATEGY_GROUP,
  aliasLabel,
  type PricingRow,
  type PricingTier,
  type TierPriceSnapshot,
  type TierRange,
  type PricingStrategyKey,
  type PricingRule
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

// ===== 编辑主定价（策略化 + 维度别名 + 阶梯矩阵）=====
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

/** 维度显示名草稿（组内全部 key；空 = 恢复默认名），随“保存定价”整组提交 */
const dimensionAliases = reactive<Record<string, string>>({})
/** 当前档位区间（编辑弹窗矩阵列头） */
const tierRanges = ref<TierRange[]>([])
/** 阶梯矩阵价格草稿（与 tierRanges 按序对位） */
const tierPrices = ref<TierPriceSnapshot[]>([])

const groupLabel = computed(() => STRATEGY_GROUP[form.serviceType]?.label || '模型计费')
const availableStrategyKeys = computed(() =>
  (STRATEGY_GROUP[form.serviceType]?.keys || []).filter(k => !form.strategies.includes(k))
)

/** 档位区间显示文本：如 [0, 128)K、[128, ∞) */
function rangeText(t: TierRange): string {
  const lo = t.lowerLimitK ?? 0
  const hi = t.unlimited ? '∞' : (t.upperLimitK ?? '?')
  return `[${lo}, ${hi})K`
}

/** 同步档位区间与矩阵草稿：区间覆盖为已保存值，价格按序保留草稿、对齐长度（新增留空） */
function syncTiers(tiers: PricingTier[]) {
  tierRanges.value = tiers.map(t => ({ lowerLimitK: t.lowerLimitK, upperLimitK: t.upperLimitK, unlimited: t.unlimited }))
  tierPrices.value = tiers.map((t, i) => tierPrices.value[i]
    ? { ...tierPrices.value[i] }
    : { inputPrice: null, cacheHitInputPrice: null, outputPrice: null,
        cacheCreateInputPrice: null, cacheHitExplicitInputPrice: null, thinkingPrice: null })
}

/** 初始化维度名草稿：组内全部 key，已保存的用别名，其余空 */
function initAliases(saved: Record<string, string> | null | undefined) {
  Object.keys(dimensionAliases).forEach(k => delete dimensionAliases[k]);
  (STRATEGY_GROUP[form.serviceType]?.keys || []).forEach(k => {
    dimensionAliases[k] = saved?.[k] || ''
  })
}

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

// ===== 整体模式：单值单价 =====
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

// ===== 阶梯模式：档位矩阵 =====
function tierPriceOf(index: number, sk: PricingStrategyKey): number | null {
  const t = tierPrices.value[index]
  if (!t) return null
  switch (sk) {
    case 'normalPrice': return t.inputPrice
    case 'output': return t.outputPrice
    case 'cacheHit': return t.cacheHitInputPrice
    case 'cacheCreate': return t.cacheCreateInputPrice
    case 'cacheHitExplicit': return t.cacheHitExplicitInputPrice
    case 'thinking': return t.thinkingPrice
    default: return null
  }
}

function setTierPrice(index: number, sk: PricingStrategyKey, v: number | undefined | null) {
  const t = tierPrices.value[index]
  if (!t) return
  const val = v ?? null
  switch (sk) {
    case 'normalPrice': t.inputPrice = val; break
    case 'output': t.outputPrice = val; break
    case 'cacheHit': t.cacheHitInputPrice = val; break
    case 'cacheCreate': t.cacheCreateInputPrice = val; break
    case 'cacheHitExplicit': t.cacheHitExplicitInputPrice = val; break
    case 'thinking': t.thinkingPrice = val; break
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
    initAliases(detail.dimensionAliases)
    syncTiers(detail.tiers)
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
    initAliases(null)
    tierRanges.value = []
    tierPrices.value = []
  }
  pricingVisible.value = true
}

function validateStrategies(): boolean {
  saveError.value = ''
  if (form.strategies.length === 0) {
    saveError.value = '请至少添加一项计费策略；不添加任何策略保存后模型将不可调用。'
    return false
  }
  if (form.billingMode === 2 && tierRanges.value.length === 0) {
    saveError.value = '阶梯计费需要先设置档位区间（计费模式行尾「档位」按钮），再填写各档单价。'
    return false
  }
  if (form.billingMode === 1) {
    // 整体模式：已添加维度的单价必填
    const priceOk = (k: PricingStrategyKey, v: number | null) => {
      if (form.strategies.includes(k) && (v == null || v < 0)) {
        saveError.value = `${aliasLabel(k, dimensionAliases)}的价格必填且不能为负`
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
  } else {
    // 阶梯模式：已添加维度的每档单价必填
    for (let i = 0; i < tierRanges.value.length; i++) {
      const tierPriceOk = (k: PricingStrategyKey, v: number | null) => {
        if (form.strategies.includes(k) && (v == null || v < 0)) {
          saveError.value = `第 ${i + 1} 档【${aliasLabel(k, dimensionAliases)}】单价必填且不能为负`
          return false
        }
        return true
      }
      if (!tierPriceOk('normalPrice', tierPrices.value[i]?.inputPrice ?? null)) return false
      if (!tierPriceOk('output', tierPrices.value[i]?.outputPrice ?? null)) return false
      if (!tierPriceOk('cacheHit', tierPrices.value[i]?.cacheHitInputPrice ?? null)) return false
      if (!tierPriceOk('cacheCreate', tierPrices.value[i]?.cacheCreateInputPrice ?? null)) return false
      if (!tierPriceOk('cacheHitExplicit', tierPrices.value[i]?.cacheHitExplicitInputPrice ?? null)) return false
      if (form.strategies.includes('thinking') && form.thinkingBillingMode === 2
          && (tierPrices.value[i]?.thinkingPrice == null || (tierPrices.value[i]?.thinkingPrice ?? 0) < 0)) {
        saveError.value = `第 ${i + 1} 档【${aliasLabel('thinking', dimensionAliases)}】单价必填且不能为负`
        return false
      }
    }
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
      '保存后定价立即生效，维度名称将对同类计费模型全局生效，是否确认？',
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
    const isTiered = form.billingMode === 2
    const payload = {
      serviceType: form.serviceType,
      modelId: form.modelId,
      vendor: form.vendor.trim() || null,
      billingMode: form.billingMode,
      // 软互斥：阶梯模式不提交主价（后端保留既有整体配置）；整体模式正常提交
      inputPrice: !isTiered && hasInput ? form.inputPrice : null,
      cacheHitInputPrice: !isTiered && hasCacheHit ? form.cacheHitInputPrice : null,
      outputPrice: !isTiered && hasOutput ? form.outputPrice : null,
      cacheCreateInputPrice: !isTiered && hasCacheCreate ? form.cacheCreateInputPrice : null,
      cacheHitExplicitInputPrice: !isTiered && hasCacheHitExplicit ? form.cacheHitExplicitInputPrice : null,
      enableInputToken: hasInput,
      enableCacheHitInput: hasCacheHit,
      enableOutputToken: hasOutput,
      enableCacheCreateInput: hasCacheCreate,
      enableCacheHitExplicitInput: hasCacheHitExplicit,
      thinkingBillingMode: hasThinking ? form.thinkingBillingMode : 1,
      // 思考单价：整体模式走主表字段，阶梯模式走 tierPrices[].thinkingPrice
      thinkingPrice: !isTiered && hasThinking && form.thinkingBillingMode === 2 ? form.thinkingPrice : null,
      discount: hasDiscount ? form.discount : null,
      dimensionAliases: { ...dimensionAliases },
      tierPrices: isTiered ? tierPrices.value.map(t => ({ ...t })) : undefined
    }
    await savePricingRequest(payload)
    ElMessage.success('定价已保存')
    pricingVisible.value = false
    await load()
  } catch (err: any) {
    saveError.value = err?.response?.data?.message || '保存失败，请检查输入或登录状态。'
  } finally { saving.value = false }
}

// ===== 阶梯档位（仅区间）=====
const tiersVisible = ref(false)
const savingTiers = ref(false)
/** 档位编辑缓存（区间） */
const tiers = ref<TierRange[]>([])
const tiersModel = ref('')
/** 档位所属模型（serviceType + modelId，主表与编辑弹窗两个入口共用） */
const tierTarget = ref<{ serviceType: string, modelId: string } | null>(null)

async function openTiers(row: PricingRow) {
  tiersModel.value = row.modelId
  await doOpenTiers(row.serviceType, row.modelId)
}

/** 从编辑定价弹窗内嵌套打开（档位变更后矩阵即时对齐） */
async function openTiersInDialog() {
  if (!form.serviceType || !form.modelId) return
  tiersModel.value = form.modelId
  await doOpenTiers(form.serviceType, form.modelId)
}

async function doOpenTiers(serviceType: string, modelId: string) {
  tierTarget.value = { serviceType, modelId }
  tiersVisible.value = true
  try {
    const detail = await getPricingDetail(serviceType, modelId)
    tiers.value = detail.tiers.length
      ? detail.tiers.map(t => ({ lowerLimitK: t.lowerLimitK, upperLimitK: t.upperLimitK, unlimited: t.unlimited }))
      : [blankTier()]
  } catch {
    tiers.value = [blankTier()]
  }
}

function blankTier(): TierRange {
  return { lowerLimitK: 0, upperLimitK: null, unlimited: true }
}

function addTier() { tiers.value.push(blankTier()) }

async function handleSaveTiers() {
  const target = tierTarget.value
  if (!target || savingTiers.value) return
  if (!tiers.value.length) { ElMessage.warning('至少保留一个档位'); return }
  try {
    await ElMessageBox.confirm('保存将整体替换该模型的档位区间（价格按序继承：超出丢弃、新增留空），是否确认？', '保存档位', { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '取消' })
  } catch { return }
  savingTiers.value = true
  try {
    await saveTiersRequest(target.serviceType, target.modelId,
      tiers.value.map(t => ({ lowerLimitK: t.lowerLimitK, upperLimitK: t.upperLimitK, unlimited: t.unlimited })))
    ElMessage.success('档位已保存')
    tiersVisible.value = false
    // 重新拉详情：编辑弹窗开着时矩阵列头即时对齐；未开时下次打开自然回显
    try {
      const detail = await getPricingDetail(target.serviceType, target.modelId)
      if (pricingVisible.value) syncTiers(detail.tiers)
    } catch { /* 详情刷新失败不影响档位保存结果 */ }
    await load()
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.message || '保存档位失败，请先保存主定价再维护档位')
  } finally { savingTiers.value = false }
}

// ===== 清除定价 =====
async function handleRemove(row: PricingRow) {
  try {
    await ElMessageBox.confirm(`清除 ${row.modelId} 的定价配置？清除后该模型将按"未配置"处理。`, '清除定价', { type: 'error', confirmButtonText: '确认清除', cancelButtonText: '取消' })
  } catch { return }
  try {
    const ok = await deletePricingRequest(row.serviceType, row.modelId)
    if (ok) { ElMessage.success('已清除定价'); await load() }
    else { ElMessage.info('未找到该模型的定价记录') }
  } catch { ElMessage.error('清除失败，请重试') }
}

// ===== 规则计费管理 =====
const rulesVisible = ref(false)
const rulesList = ref<PricingRule[]>([])
const rulesModelName = ref('')
const rulesRow = ref<PricingRow | null>(null)

// 规则编辑弹窗
const ruleEditVisible = ref(false)
const ruleEditId = ref<number | null>(null)
const ruleEditData = ref({ ruleName: '', matchJson: '', priceJson: '', priority: 100 })
const ruleEditGroupKey = ref('text')
const ruleSaving = ref(false)

const GROUP_OF_SERVICE_TYPE: Record<string, string> = {
  chat: 'text', embedding: 'text', rerank: 'text',
  tts: 'voice', stt: 'voice',
  imgGen: 'image', imgEdit: 'image'
}

async function openRules(row: PricingRow) {
  rulesRow.value = row
  rulesModelName.value = row.modelId
  ruleEditGroupKey.value = GROUP_OF_SERVICE_TYPE[row.serviceType] || 'text'
  rulesVisible.value = true
  try {
    rulesList.value = await getRules(row.modelId)
  } catch {
    rulesList.value = []
    ElMessage.error('加载规则列表失败')
  }
}

function formatMatch(json: string): string {
  try {
    const obj = JSON.parse(json)
    if (!obj.conditions || obj.conditions.length === 0) return '无条件（兜底）'
    return obj.conditions.map((c: any) => {
      if (c.operator) return `${c.operator}(...)`
      return `${c.field} ${c.op} ${Array.isArray(c.value) ? c.value.join('~') : c.value}`
    }).join(' 且 ')
  } catch { return '—' }
}

function formatPrice(json: string): string {
  try {
    const obj = JSON.parse(json)
    return Object.entries(obj).map(([k, v]) => `${k}=${v}`).join(', ')
  } catch { return '—' }
}

async function addRule() {
  ruleEditId.value = null
  ruleEditData.value = {
    ruleName: '',
    matchJson: JSON.stringify({ operator: 'AND', conditions: [] }),
    priceJson: '{}',
    priority: (rulesList.value.length + 1) * 10
  }
  ruleEditVisible.value = true
}

async function editRule(rule: PricingRule) {
  ruleEditId.value = rule.id
  ruleEditData.value = {
    ruleName: rule.ruleName,
    matchJson: rule.matchJson,
    priceJson: rule.priceJson,
    priority: rule.priority
  }
  ruleEditVisible.value = true
}

async function saveRule() {
  if (!ruleEditData.value.ruleName.trim()) { ElMessage.warning('规则名称必填'); return }
  if (!rulesRow.value) return
  ruleSaving.value = true
  try {
    if (ruleEditId.value) {
      await updateRule(ruleEditId.value, {
        ruleName: ruleEditData.value.ruleName,
        matchJson: ruleEditData.value.matchJson,
        priceJson: ruleEditData.value.priceJson
      })
    } else {
      await createRule({
        serviceType: rulesRow.value.serviceType,
        modelName: rulesRow.value.modelId,
        ruleName: ruleEditData.value.ruleName,
        matchJson: ruleEditData.value.matchJson,
        priceJson: ruleEditData.value.priceJson,
        priority: ruleEditData.value.priority
      })
    }
    ElMessage.success('规则已保存')
    ruleEditVisible.value = false
    rulesList.value = await getRules(rulesRow.value.modelId)
    await load()
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.message || '保存规则失败')
  } finally { ruleSaving.value = false }
}

async function deleteRuleRow(rule: PricingRule, index: number) {
  try {
    await ElMessageBox.confirm(`删除规则「${rule.ruleName}」？`, '删除规则', { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' })
  } catch { return }
  try {
    await deleteRule(rule.id)
    ElMessage.success('已删除')
    rulesList.value = await getRules(rulesRow.value!.modelId)
    await load()
  } catch { ElMessage.error('删除失败') }
}

async function handleSaveOrder() {
  if (!rulesRow.value) return
  try {
    await reorderRules(rulesList.value.map(r => r.id))
    ElMessage.success('排序已保存')
    rulesList.value = await getRules(rulesRow.value.modelId)
    await load()
  } catch { ElMessage.error('保存排序失败') }
}

function toggleExpand(row: PricingRule) {
  // 预留：点击行展开详情
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
.btn-icon { margin-left: 4px; vertical-align: -2px }
.strategy-list { display: flex; flex-direction: column; gap: 10px }
.strategy-row { display: flex; align-items: flex-start; gap: 12px; padding: 10px 12px; background: #f7fafc; border: 1px solid #e4edf3; border-radius: 8px }
.name-input { width: 220px; flex-shrink: 0 }
.remove-btn { margin-left: auto; flex-shrink: 0; margin-top: 4px }
.strategy-options { display: flex; flex-direction: column; gap: 4px }
.strategy-option { padding: 8px 10px; border-radius: 6px; cursor: pointer }
.strategy-option:hover { background: #ecf5ff }
.strategy-opt-label { font-weight: 600 }
.strategy-empty { padding: 8px 0 }
/* 阶梯矩阵：横向排列，多档时横向滚动 */
.tier-matrix { display: flex; gap: 10px; overflow-x: auto; padding: 2px 0; flex: 1; min-width: 0 }
.tier-cell { flex-shrink: 0; width: 168px; display: flex; flex-direction: column; gap: 4px }
.tier-head { font-size: 12px; color: #8296a7; white-space: nowrap }
.tier-input { width: 100% }
.tier-empty { font-size: 12px; color: #b88230; padding: 8px 4px; white-space: nowrap }
.rule-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px }
</style>
