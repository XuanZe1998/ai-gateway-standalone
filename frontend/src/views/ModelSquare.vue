<template>
  <section class="page-shell model-square">
    <header class="square-hero">
      <div><p class="eyebrow">EXPLORE · BUILD · CONNECT</p><h1>模型广场</h1><p class="hero-description">为每一种灵感，找到合适的模型。了解真实收费，复制地址即可开始接入。</p><div class="hero-pills"><span>统一网关接入</span><span>账户适用价格</span><span>安全密钥调用</span></div></div>
      <div class="hero-count"><strong>{{ models.length }}</strong><span>个已配置可接入模型</span></div>
    </header>
    <div class="catalog-toolbar">
      <el-input v-model="search" placeholder="搜索模型名称或模型 ID" clearable aria-label="搜索模型" class="model-search"><template #prefix><el-icon><Search /></el-icon></template></el-input>
      <el-select v-model="service" clearable placeholder="全部服务类型" aria-label="服务类型"><el-option v-for="s in services" :key="s" :label="serviceLabels[s] || s" :value="s" /></el-select>
      <el-select v-model="vendor" clearable placeholder="全部厂商" aria-label="厂商"><el-option v-for="v in vendors" :key="v" :label="v" :value="v" /></el-select>
      <el-button :loading="loading" @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
    </div>
    <div class="catalog-caption"><span>{{ filtered.length }} 个模型<span v-if="search || service || vendor">匹配筛选</span></span><span>人民币计价 · 真实收费规则 · 不自动发起调用</span></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="gap"><template #default><el-button text @click="load">重新加载</el-button></template></el-alert>
    <div v-if="loading" class="model-grid"><el-skeleton v-for="i in 6" :key="i" animated class="model-skeleton"><template #template><el-skeleton-item variant="h1" /><el-skeleton-item variant="text" /><el-skeleton-item variant="p" style="height:70px;margin-top:20px" /><el-skeleton-item variant="rect" style="height:90px;margin-top:20px" /></template></el-skeleton></div>
    <template v-else-if="!error">
      <div v-if="paged.length" class="model-grid">
        <article v-for="model in paged" :key="model.serviceType + ':' + model.modelId" class="model-card">
          <div class="model-identity"><div class="model-glyph" :class="'glyph-' + model.serviceType"><el-icon><component :is="icons[model.serviceType] || Cpu" /></el-icon></div><div class="identity-text"><h2 :title="model.displayName">{{ model.displayName }}</h2><p :title="model.modelId">{{ model.modelId }}</p></div></div>
          <div class="capability-tags"><el-tag size="small" effect="plain">{{ serviceLabels[model.serviceType] || model.serviceType }}</el-tag><el-tag v-for="tag in model.tags" :key="tag" size="small" type="info" effect="plain">{{ tag }}</el-tag></div>
          <p class="model-intro">{{ model.description }}</p>
          <div class="vendor-line"><span :title="model.vendors.join(' / ')">{{ model.vendors.join(' / ') || '厂商信息待完善' }}</span><el-tag v-if="model.freeQuotaApplicable" type="success" size="small" effect="plain">适用免费额度</el-tag></div>
          <div class="card-pricing">
            <div class="price-heading"><span>账户适用单价</span><span v-if="model.priceStatus === 'MULTIPLE'" class="scheme-badge">多方案计费</span></div>
            <template v-if="model.priceSummary.length"><div v-for="row in model.priceSummary" :key="row.item" class="price-row"><span>{{ row.item }}</span><span><b>{{ priceText(row) }}</b><small v-if="row.status === 'CHARGED'">{{ row.unit }}</small></span></div></template>
            <div v-else class="price-placeholder"><strong>{{ model.priceStatus === 'UNKNOWN' ? '价格暂不可确认' : model.priceStatus === 'MULTIPLE' ? model.schemeCount + ' 个收费方案' : '按条件 / 阶梯计费' }}</strong><p>{{ model.priceStatus === 'UNKNOWN' ? '缺失配置不代表免费' : '查看明细了解全部价格与适用条件' }}</p></div>
          </div>
          <div class="card-actions"><el-button @click="open(model, 'details')">查看详情</el-button><el-button type="primary" @click="open(model, 'connect')"><el-icon><Link /></el-icon>一键接入</el-button></div>
        </article>
      </div>
      <el-empty v-else :description="models.length ? '没有匹配的模型，试试调整筛选条件' : '暂无可接入模型，请联系管理员检查配置'"><el-button v-if="models.length" @click="clearFilters">清空筛选</el-button></el-empty>
      <el-pagination v-if="filtered.length > 12" v-model:current-page="page" :page-size="12" :total="filtered.length" layout="prev, pager, next" class="catalog-pagination" />
    </template>
    <p class="catalog-disclaimer">价格为当前配置快照。实际费用取决于用量、路由、免费额度与账户规则，以最终账单为准。</p>
    <el-drawer v-model="drawer" :size="smallScreen ? '100%' : 'min(820px, 90vw)'" :title="selected?.displayName || '模型详情'" destroy-on-close @closed="closeDetail">
      <el-skeleton v-if="detailLoading" :rows="8" animated />
      <el-alert v-else-if="detailError" :title="detailError" type="error" :closable="false" show-icon><template #default><el-button text @click="retryDetail">重试</el-button></template></el-alert>
      <div v-else-if="detail" class="detail-content">
        <div class="detail-model-id"><span>实际模型 ID</span><code>{{ detail.model.modelId }}</code></div>
        <el-tabs v-model="activeTab">
          <el-tab-pane label="模型与详细收费" name="details">
            <h3>模型介绍</h3><p class="full-description">{{ detail.model.description }}</p><div class="detail-tags"><el-tag>{{ serviceLabels[detail.model.serviceType] }}</el-tag><el-tag v-for="t in detail.model.tags" :key="t" type="info">{{ t }}</el-tag></div>
            <div class="quota-note">{{ detail.model.freeQuotaApplicable ? '本服务适用月度免费额度，剩余额度请在工作台查看。' : '本服务不适用当前免费额度配置。' }}</div>
            <ModelPricingTable :schemes="detail.schemes" />
            <ul class="detail-notes"><li v-for="note in detail.notes" :key="note">{{ note }}</li></ul>
          </el-tab-pane>
          <el-tab-pane label="接入指南" name="connect">
            <el-alert title="只复制接入配置，不自动创建 Key 或发起收费调用" type="info" :closable="false" show-icon />
            <div class="connection-field"><label>OpenAI 客户端常用 Base URL</label><div><code>{{ origin }}/v1</code><el-button size="small" @click="copy(origin + '/v1')">复制</el-button></div></div>
            <div class="connection-field"><label>本服务接口 URL · {{ detail.access.method }}</label><div><code>{{ origin + detail.access.path }}</code><el-button size="small" @click="copy(origin + detail.access.path)">复制</el-button></div></div>
            <div class="connection-field"><label>实际模型 ID</label><div><code>{{ detail.model.modelId }}</code><el-button size="small" @click="copy(detail.model.modelId)">复制</el-button></div></div>
            <p class="connection-note">{{ detail.access.note }}</p>
            <p class="connection-note">将 <code>YOUR_KEY</code> 替换为您安全保存的密钥。文件路径、音色等占位符需要按模型支持的参数填写。</p>
            <div class="sample-heading"><h3>cURL 调用示例</h3><el-button size="small" @click="copy(example)">复制示例</el-button></div><pre class="connection-sample">{{ example }}</pre>
            <el-alert v-if="detail.access.queryPath" title="视频为异步任务：先创建，再用返回的任务 ID 查询；成功结果按实际用量结算。" type="warning" :closable="false" />
            <el-button type="primary" plain class="keys-link" @click="$router.push('/me/keys')">前往我的 Key</el-button>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-drawer>
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Cpu, DataLine, Headset, Link, Picture, Refresh, Search, Sort, VideoCamera } from '@element-plus/icons-vue'
import ModelPricingTable from '@/components/ModelPricingTable.vue'
import { getModelSquare, getModelDetail, type ModelCard, type ModelDetail } from '@/api/modelSquare'
import { connectionExample, copyText, filterModels, priceText, serviceLabels } from '@/utils/modelSquare'
const origin = window.location.origin
const icons: Record<string, typeof Cpu> = { chat: ChatDotRound, embedding: DataLine, rerank: Sort, tts: Headset, stt: Headset, imgGen: Picture, imgEdit: Picture, vidGen: VideoCamera }
const models = ref<ModelCard[]>([]), loading = ref(false), error = ref('')
const search = ref(''), service = ref(''), vendor = ref(''), page = ref(1)
const services = computed(() => [...new Set(models.value.map(m => m.serviceType))])
const vendors = computed(() => [...new Set(models.value.flatMap(m => m.vendors))].sort())
const filtered = computed(() => filterModels(models.value, search.value, service.value, vendor.value))
const paged = computed(() => filtered.value.slice((page.value - 1) * 12, page.value * 12))
watch([search, service, vendor], () => { page.value = 1 })
watch(() => filtered.value.length, n => { page.value = Math.min(page.value, Math.max(1, Math.ceil(n / 12))) })
const drawer = ref(false), detailLoading = ref(false), detailError = ref(''), detail = ref<ModelDetail | null>(null), selected = ref<ModelCard | null>(null), activeTab = ref('details')
const media = window.matchMedia('(max-width: 600px)'), smallScreen = ref(media.matches)
const resize = () => { smallScreen.value = media.matches }
let detailRequest = 0
async function load() { loading.value = true; error.value = ''; try { models.value = await getModelSquare() } catch { error.value = '加载模型广场失败，请重试或重新登录。' } finally { loading.value = false } }
async function open(model: ModelCard, tab: string) { selected.value = model; activeTab.value = tab; drawer.value = true; await retryDetail() }
async function retryDetail() {
  if (!selected.value) return
  const ticket = ++detailRequest, model = selected.value
  detailLoading.value = true; detailError.value = ''; detail.value = null
  try { const value = await getModelDetail(model.serviceType, model.modelId); if (ticket === detailRequest) detail.value = value }
  catch { if (ticket === detailRequest) detailError.value = '加载模型详情失败；模型可能已下架，请重试或刷新目录。' }
  finally { if (ticket === detailRequest) detailLoading.value = false }
}
function closeDetail() { ++detailRequest; detail.value = null; selected.value = null; detailLoading.value = false }
function clearFilters() { search.value = ''; service.value = ''; vendor.value = '' }
const example = computed(() => detail.value ? connectionExample(detail.value, origin) : '')
async function copy(text: string) { if (await copyText(text)) ElMessage.success('已复制'); else ElMessage.warning('复制失败，请手动选中并复制下方内容') }
onMounted(() => { media.addEventListener('change', resize); load() })
onUnmounted(() => { ++detailRequest; media.removeEventListener('change', resize) })
</script>
<style scoped>
.model-square{max-width:1600px;margin:0 auto;color:#18354d}.square-hero{display:flex;align-items:center;justify-content:space-between;gap:28px;padding:32px 36px;border:1px solid #dbe9f2;border-radius:20px;background:radial-gradient(ellipse at 90% 0%,#cceef1 0,transparent 55%),linear-gradient(130deg,#f6faff,#eaf2fc);margin-bottom:24px}.square-hero h1{font-size:32px;letter-spacing:-.04em;margin:8px 0 12px}.hero-description{font-size:14px;line-height:1.8;color:#607b91;margin:0}.hero-pills{display:flex;gap:8px;flex-wrap:wrap;margin-top:18px}.hero-pills span{padding:5px 10px;border:1px solid #d2e3ed;background:#ffffffa8;border-radius:99px;font-size:11px;color:#49718a}.hero-count{min-width:160px;text-align:right}.hero-count strong{display:block;color:#167c91;font-size:46px;font-weight:700;line-height:1.1}.hero-count span{font-size:12px;color:#607b91}.catalog-toolbar{display:flex;align-items:center;gap:12px;padding:16px;background:#fff;border:1px solid #e2eaf2;border-radius:14px}.catalog-toolbar .el-select{width:165px;flex-shrink:0}.model-search{max-width:460px;flex:1}.catalog-caption{display:flex;justify-content:space-between;gap:12px;color:#8395a5;font-size:12px;padding:18px 2px}.model-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:20px}.model-card{display:flex;flex-direction:column;min-width:0;padding:24px;border:1px solid #e0e9f1;border-radius:16px;background:#fff;box-shadow:0 3px 14px #24446105;transition:box-shadow .2s,border-color .2s}.model-card:hover{border-color:#91cbd7;box-shadow:0 10px 25px #17496312}.model-identity{display:flex;align-items:center;gap:12px;min-width:0}.model-glyph{width:44px;height:44px;flex-shrink:0;display:grid;place-items:center;border-radius:13px;background:#e9f2ff;color:#327adc;font-size:22px}.glyph-embedding,.glyph-rerank{background:#e8f7f5;color:#079184}.glyph-tts,.glyph-stt{background:#f2edff;color:#7960bd}.glyph-imgGen,.glyph-imgEdit,.glyph-vidGen{background:#fff3e7;color:#ce8640}.identity-text{min-width:0}.identity-text h2{font-size:17px;line-height:1.5;margin:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.identity-text p{margin:3px 0 0;font:11px/1.5 ui-monospace,SFMono-Regular,Consolas,monospace;color:#8798a9;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.capability-tags{height:50px;display:flex;gap:6px;align-content:flex-start;flex-wrap:wrap;overflow:hidden;margin-top:18px}.model-intro{font-size:13px;line-height:1.8;color:#698194;margin:10px 0 16px;height:47px;overflow:hidden;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow-wrap:anywhere}.vendor-line{display:flex;justify-content:space-between;gap:8px;align-items:center;height:24px;margin-bottom:16px;font-size:11px;color:#8b9ba9}.vendor-line>span:first-child{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.card-pricing{height:118px;padding:14px;border-radius:11px;background:#f6f9fc;border:1px solid #edf2f7;margin-top:auto}.price-heading{display:flex;justify-content:space-between;color:#8394a4;font-size:11px;margin-bottom:10px}.scheme-badge{color:#1c8f95}.price-row{display:flex;justify-content:space-between;gap:8px;margin:8px 0;font-size:12px;color:#7b8b9a}.price-row>span:last-child{text-align:right}.price-row b{color:#1b5068;font-size:13px;font-variant-numeric:tabular-nums}.price-row small{font-size:9px;margin-left:5px}.price-placeholder strong{font-size:14px;color:#47677f}.price-placeholder p{font-size:11px;color:#93a3b1;margin:9px 0 0}.card-actions{display:flex;gap:10px;margin-top:20px}.card-actions .el-button{flex:1;margin-left:0;padding-left:6px;padding-right:6px}.model-skeleton{padding:24px;background:#fff;border:1px solid #e0e9f1;border-radius:16px;min-height:340px}.catalog-pagination{justify-content:center;margin-top:28px}.catalog-disclaimer{font-size:11px;text-align:center;color:#96a5b2;margin:24px 0}.detail-model-id{padding:14px;background:#f2f7fb;border-radius:12px;margin-bottom:18px;display:flex;flex-direction:column;gap:8px}.detail-model-id span{font-size:11px;color:#8395a5}.detail-model-id code{overflow-wrap:anywhere;font-size:13px}.full-description{white-space:pre-wrap;overflow-wrap:anywhere;font-size:14px;line-height:1.9;color:#60778b}.detail-tags{display:flex;gap:8px;flex-wrap:wrap;margin:16px 0}.quota-note{padding:12px;border-radius:10px;background:#ebf8f4;color:#397965;font-size:12px;line-height:1.7}.detail-notes{font-size:12px;color:#8395a5;padding-left:18px;line-height:1.9}.connection-field{margin:22px 0}.connection-field label{display:block;font-size:12px;color:#708a9e;margin-bottom:8px}.connection-field>div{display:flex;gap:12px;align-items:center;padding:12px;background:#f3f7fb;border-radius:10px}.connection-field code{flex:1;min-width:0;overflow-wrap:anywhere;font-size:12px;user-select:text}.connection-note{font-size:12px;color:#728b9f;line-height:1.9}.sample-heading{display:flex;align-items:center;justify-content:space-between}.sample-heading h3{font-size:15px}.connection-sample{padding:20px;background:#102a3d;color:#d8f0f4;border-radius:12px;white-space:pre-wrap;overflow-wrap:anywhere;font:12px/1.9 ui-monospace,SFMono-Regular,Consolas,monospace;user-select:text}.keys-link{margin-top:20px}
@media(min-width:1600px){.model-grid{grid-template-columns:repeat(4,minmax(0,1fr))}}
@media(max-width:1150px){.model-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.catalog-toolbar{flex-wrap:wrap}.model-search{max-width:none;flex-basis:100%}.hero-count{min-width:120px}}
@media(max-width:600px){.square-hero{padding:24px;align-items:flex-start}.square-hero h1{font-size:27px}.hero-count{display:none}.model-grid{grid-template-columns:1fr}.catalog-toolbar{gap:10px}.catalog-toolbar .el-select{flex:1;width:auto;min-width:110px}.catalog-toolbar .el-button{width:100%}.catalog-caption{flex-direction:column;font-size:11px}.model-card{padding:22px}.catalog-disclaimer{line-height:1.9}.model-square :deep(.el-drawer__body){padding:16px}.detail-content :deep(.el-tabs__item){font-size:13px}}
</style>
