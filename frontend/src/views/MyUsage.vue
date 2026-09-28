<template>
  <section class="page-shell"><div class="page-heading"><div><p class="eyebrow">USAGE & BILLING</p><h1>我的用量</h1><p>按本人计费账户聚合，记录可能包含试验场和外部平台 Key 的调用。</p></div><el-button @click="load">刷新</el-button></div>
    <el-card class="gap"><div class="filter-bar"><el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" /><el-input v-model="model" placeholder="模型名称" clearable /><el-select v-model="keyId" placeholder="全部 Key" clearable><el-option v-for="key in keys" :key="key.keyId" :label="key.name" :value="key.keyId" /></el-select><el-button type="primary" @click="page = 0; load()">筛选</el-button></div></el-card>
    <el-alert v-if="error" :title="error" type="error" show-icon class="gap" />
    <div class="metric-grid compact"><el-card class="metric"><span>请求数</span><strong>{{ data?.summary.requests ?? '—' }}</strong></el-card><el-card class="metric"><span>Token 总计</span><strong>{{ fmt(data?.summary.tokens) }}</strong></el-card><el-card class="metric"><span>账单费用</span><strong>{{ data ? `¥ ${data.summary.cost}` : '—' }}</strong></el-card></div>
    <el-card v-loading="loading"><template #header><b>账单明细</b></template><el-table :data="data?.records || []" empty-text="当前筛选范围没有调用记录"><el-table-column prop="time" label="时间" min-width="175" /><el-table-column prop="model" label="模型" min-width="150" /><el-table-column prop="service" label="服务" width="110" /><el-table-column prop="tokens" label="Token" width="110" /><el-table-column prop="cost" label="费用 (¥)" width="110" /><el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.success ? 'success' : 'danger'">{{ row.success ? '成功' : '失败' }}</el-tag></template></el-table-column></el-table><div class="pager"><el-pagination :current-page="page + 1" :page-size="20" :total="Number(data?.summary.requests || 0)" layout="prev, pager, next" @current-change="(n: number) => { page = n - 1; load() }" /></div></el-card>
  </section>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getKeys, getUsage, type CampusKey, type UsageInfo } from '@/api/selfService'
const dates = ref<[string, string] | null>(null), model = ref(''), keyId = ref(''), page = ref(0)
const keys = ref<CampusKey[]>([]), data = ref<UsageInfo>(), error = ref(''), loading = ref(false)
const fmt = (n?: number) => n == null ? '—' : Number(n).toLocaleString('zh-CN')
async function load() { loading.value = true; try { data.value = await getUsage({ from: dates.value?.[0], to: dates.value?.[1], model: model.value || undefined, keyId: keyId.value || undefined, page: page.value, size: 20 }); error.value = '' } catch { error.value = '加载个人用量失败' } finally { loading.value = false } }
onMounted(async () => { await load(); try { keys.value = (await getKeys()).keys } catch { /* Usage remains available without key filter. */ } })
</script>
