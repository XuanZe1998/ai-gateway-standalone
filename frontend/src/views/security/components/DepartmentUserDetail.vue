<template>
  <el-dialog :model-value="modelValue" title="个人用量与额度详情" width="min(1180px, 97vw)" @update:model-value="$emit('update:modelValue', $event)">
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="gap" />
    <div v-loading="loading">
      <template v-if="data">
        <el-descriptions :column="columns" border><el-descriptions-item label="姓名">{{ data.profile.display_name }}</el-descriptions-item><el-descriptions-item label="账号">{{ data.profile.account }}</el-descriptions-item><el-descriptions-item label="当前部门">{{ data.profile.department_name }}</el-descriptions-item><el-descriptions-item label="使用方式">{{ data.profile.free_usage ? '免费使用' : '正常计费' }}</el-descriptions-item><el-descriptions-item label="规则来源">{{ source(data.profile.policy_source) }}</el-descriptions-item><el-descriptions-item label="额度来源">{{ funding(data.profile.funding_source) }}</el-descriptions-item><el-descriptions-item label="账户余额">{{ data.account.length ? '¥ ' + money(data.account[0].balance) : '未建立账户' }}<el-tag v-if="data.profile.user_type === 1" size="small">企业共享</el-tag></el-descriptions-item><el-descriptions-item label="月度免费 Token 已用">{{ fmt(data.quota[0]?.used_quota) }}</el-descriptions-item><el-descriptions-item label="月度免费 Token 剩余">{{ fmt(data.quota[0]?.remaining_quota) }} / {{ fmt(data.quota[0]?.total_quota) }}</el-descriptions-item><el-descriptions-item label="每月使用金额上限">{{ data.profile.monthly_cost == null ? '不限制' : '¥ ' + money(data.profile.monthly_cost) }}</el-descriptions-item><el-descriptions-item label="每月 Token 上限">{{ data.profile.monthly_tokens == null ? '不限制' : fmt(data.profile.monthly_tokens) }}</el-descriptions-item><el-descriptions-item label="本月使用金额（含预占）">¥ {{ money(data.budgetUsage?.value) }}</el-descriptions-item><el-descriptions-item label="未计价预算记录">{{ fmt(data.budgetUsage?.unknown_value) }} 笔</el-descriptions-item></el-descriptions>
        <p class="muted">以下用量遵循主页面筛选：{{ filters.start }} 至 {{ filters.end }}。资料与额度显示当前状态。使用金额按免费减免前的正常计费规则计量，免费用户实际扣费仍为 0；未计价的旧免费记录不追算。</p>
        <el-tabs v-model="tab">
          <el-tab-pane label="身份资料" name="identity"><el-descriptions :column="columns" border><el-descriptions-item v-for="(label, key) in identityLabels" :key="key" :label="label">{{ data.identity?.[key] ?? '—' }}</el-descriptions-item></el-descriptions></el-tab-pane>
          <el-tab-pane label="消耗趋势" name="trends"><div ref="chartElement" class="usage-chart" /><AppTable :data="data.trends" pagination-key="department-user-trends"><el-table-column prop="date" label="日期" /><el-table-column label="Token"><template #default="{ row }">{{ fmt(row.tokens) }}</template></el-table-column><el-table-column label="费用 ¥"><template #default="{ row }">{{ money(row.cost) }}</template></el-table-column><el-table-column prop="requests" label="账单数" /></AppTable></el-tab-pane>
          <el-tab-pane label="模型用量" name="models"><AppTable :data="data.models" pagination-key="department-user-models"><el-table-column prop="model_name" label="模型" min-width="200" /><el-table-column prop="service_type" label="服务" /><el-table-column label="Token"><template #default="{ row }">{{ fmt(row.tokens) }}</template></el-table-column><el-table-column label="费用 ¥"><template #default="{ row }">{{ money(row.cost) }}</template></el-table-column></AppTable></el-tab-pane>
          <el-tab-pane label="Key 用量" name="keys"><AppTable :data="data.keys" pagination-key="department-user-keys"><el-table-column label="Key 名称" min-width="160"><template #default="{ row }">{{ row.name || '会话调用／未记录名称' }}</template></el-table-column><el-table-column prop="api_key_id" label="Key ID" min-width="250" /><el-table-column label="Token"><template #default="{ row }">{{ fmt(row.tokens) }}</template></el-table-column><el-table-column label="费用 ¥"><template #default="{ row }">{{ money(row.cost) }}</template></el-table-column></AppTable></el-tab-pane>
          <el-tab-pane label="账单明细" name="bills"><AppTable :data="data.bills" :paginated="false"><el-table-column type="expand"><template #default="{ row }"><p class="measurement">{{ measurements(row.usage_detail) }}</p></template></el-table-column><el-table-column prop="started_at" label="时间" min-width="175" /><el-table-column prop="model_name" label="模型" min-width="170" /><el-table-column label="归属部门" min-width="160"><template #default="{ row }">{{ row.department_name || '历史归属未知' }}</template></el-table-column><el-table-column label="额度来源" min-width="150"><template #default="{ row }">{{ funding(row.funding_source) }}</template></el-table-column><el-table-column label="Token" min-width="100"><template #default="{ row }">{{ row.total_tokens == null ? '非 Token 计量' : fmt(row.total_tokens) }}</template></el-table-column><el-table-column label="账单费用 ¥" min-width="140"><template #default="{ row }">{{ money(row.total_cost) }}</template></el-table-column><el-table-column label="使用金额 ¥" min-width="140"><template #default="{ row }">{{ row.usage_value == null ? '计价未知' : money(row.usage_value) }}</template></el-table-column><el-table-column label="实际扣费 ¥" min-width="140"><template #default="{ row }">{{ row.actual_deducted == null ? '未确认' : money(row.actual_deducted) }}</template></el-table-column><el-table-column label="结算状态" min-width="110"><template #default="{ row }"><el-tag :type="['SETTLED','EXEMPT'].includes(row.settlement_status) ? 'success' : 'warning'">{{ settlement(row.settlement_status) }}</el-tag></template></el-table-column></AppTable><AppPagination v-model:current-page="billPage" v-model:page-size="billSize" :total="data.billTotal" @current-change="load" @size-change="billPage = 1; load()" /></el-tab-pane>
        </el-tabs>
      </template>
    </div>
  </el-dialog>
</template>
<script setup lang="ts">
import { nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { getUser, formatMoney as money, formatTokens as fmt, fundingSource as funding, policySource as source, settlementLabel as settlement, type UsageFilters, type UserDetail } from '@/api/departmentAdmin'
const identityLabels = { ownerId: '用户 ID', platformUserId: '计费用户 ID', campusAccount: '校园账号', localAccount: '本地账号', staffNo: '学工号', mobile: '手机号', department: '当前部门', departmentCode: '部门编码', typeName: '校园身份', typeCode: '身份编码', roles: '角色', verifyStatus: '实名状态', companyId: '企业 ID', enabled: '校园身份启用', totalQuota: '月度免费 Token 总额', usedQuota: '月度免费 Token 已用', remainingQuota: '月度免费 Token 剩余', periodEnd: '额度周期结束', freeUsageReason: '使用方式说明', policyUpdatedBy: '规则设置人', policyUpdatedAt: '规则设置时间' }
const props = defineProps<{ modelValue: boolean; owner?: number; filters: UsageFilters }>()
defineEmits<{ 'update:modelValue': [value: boolean] }>()
const columns = ref(window.innerWidth < 700 ? 1 : 3)
function resizeColumns() { columns.value = window.innerWidth < 700 ? 1 : 3 }
onMounted(() => window.addEventListener('resize', resizeColumns))
const data = ref<UserDetail>(), error = ref(''), loading = ref(false), tab = ref('trends'), chartElement = ref<HTMLDivElement>(), billPage = ref(1), billSize = ref(20)
let revision = 0, chart: echarts.ECharts | undefined, observer: ResizeObserver | undefined
async function load() {
  if (!props.owner || !props.modelValue) return
  const current = ++revision; loading.value = true; error.value = ''
  try { const result = await getUser(props.owner, props.filters, billPage.value - 1, billSize.value); if (current !== revision) return; data.value = result; await renderChart() }
  catch (e: any) { if (current === revision) { error.value = e.response?.data?.message || e.message || '加载个人详情失败'; data.value = undefined } }
  finally { if (current === revision) loading.value = false }
}
async function renderChart() {
  await nextTick(); if (!chartElement.value || !data.value) return
  chart ??= echarts.init(chartElement.value)
  chart.setOption({ tooltip: { trigger: 'axis' }, legend: { data: ['Token','费用 ¥'] }, grid: { left: 75, right: 80, bottom: 40 }, xAxis: { type: 'category', data: data.value.trends.map(r => r.date) }, yAxis: [{ type: 'value', name: 'Token' }, { type: 'value', name: '费用 ¥' }], series: [{ name: 'Token', type: 'bar', data: data.value.trends.map(r => Number(r.tokens)) }, { name: '费用 ¥', type: 'line', yAxisIndex: 1, data: data.value.trends.map(r => Number(r.cost)) }] })
  observer ??= new ResizeObserver(() => chart?.resize()); observer.observe(chartElement.value); chart.resize()
}
function measurements(value: unknown) {
  if (!value) return '没有单独记录非 Token 计量维度'
  try { const usage = typeof value === 'string' ? JSON.parse(value) : value; return `计量单位：${usage.billingUnit || '未记录'}；` + (usage.items || []).map((r: any) => [r.resolution, r.count == null ? '' : `${r.count} 张`, r.seconds == null ? '' : `${r.seconds} 秒`, r.tokens == null ? '' : `${fmt(r.tokens)} Token`, r.amount == null ? '' : `¥ ${money(r.amount)}`].filter(Boolean).join(' · ')).join('；') } catch { return '计量明细暂不可读，请核对原账单' }
}
watch(() => [props.modelValue, props.owner, props.filters], () => { if (props.modelValue) { billPage.value = 1; chart?.dispose(); chart = undefined; observer?.disconnect(); data.value = undefined; void load() } else { ++revision; chart?.dispose(); chart = undefined; observer?.disconnect() } }, { deep: true })
watch(tab, () => { if (tab.value === 'trends') void renderChart() })
onBeforeUnmount(() => { window.removeEventListener('resize', resizeColumns); ++revision; chart?.dispose(); observer?.disconnect() })
</script>
<style scoped>.usage-chart{height:320px;width:100%}.muted{font-size:12px;color:var(--el-text-color-secondary)}.measurement{padding:12px 24px}:deep(.el-tabs){margin-top:20px}:deep(.el-pagination){margin-top:18px}</style>
