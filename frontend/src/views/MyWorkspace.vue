<template>
  <section class="page-shell">
    <div class="page-heading"><div><p class="eyebrow">MY WORKSPACE</p><h1>我的工作台</h1><p>欢迎回来，{{ user?.displayName || user?.account }}。在这里掌握您的用量与接入状态。</p></div><el-button type="primary" @click="$router.push('/me/keys')">创建 API Key</el-button></div>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="gap" />
    <el-alert v-if="balance && !balance.verified" title="尚未完成实名认证，模型调用将被拒绝；请先在算力平台完成认证。" type="warning" show-icon :closable="false" class="gap" />
    <el-alert v-if="balance && balance.quota.exists && Number(balance.quota.remaining) <= 0 && (!balance.paid.exists || Number(balance.paid.balance) <= 0)" title="免费额度已用尽且付费账户无可用余额，模型调用将被拒绝。请联系平台管理员处理。" type="warning" show-icon :closable="false" class="gap" />
    <div v-loading="loading" class="metric-grid">
      <el-card class="metric"><span>月度免费额度</span><strong>{{ balance?.quota.exists ? format(balance.quota.remaining) : '未建立' }}</strong><small>{{ balance?.quota.exists ? `总额 ${format(balance.quota.total)} · 至 ${balance.quota.periodEnd?.slice(0, 10)}` : '完成实名后将按配置初始化' }}</small></el-card>
      <el-card class="metric"><span>{{ balance?.paid.shared ? '企业共享余额' : '付费账户余额' }}</span><strong>{{ balance?.paid.exists ? `¥ ${balance.paid.balance}` : '无余额记录' }}</strong><small>{{ balance?.paid.shared ? '与企业成员共享，不是个人余额' : '余额不足时请联系平台管理员' }}</small></el-card>
      <el-card class="metric"><span>占用名额的 Key</span><strong>{{ keys?.count ?? '—' }} <em>/ {{ keys?.limit ?? '—' }}</em></strong><small>停用仍占用名额 · <router-link to="/me/keys">管理密钥</router-link></small></el-card>
      <el-card class="metric"><span>近 7 天 Token</span><strong>{{ format(usage?.summary.tokens) }}</strong><small>{{ usage?.summary.requests ?? '—' }} 次请求 · <router-link to="/me/usage">查看明细</router-link></small></el-card>
    </div>
    <div class="workspace-grid">
      <el-card><template #header><b>快速开始 · 可用模型</b></template><ol class="steps"><li>在「我的 Key」创建并安全保存凭据</li><li>在「模型广场」选择可用模型与服务类型</li><li>使用网关 API 地址调用，在「我的用量」检查账单</li></ol><div v-if="models.length" class="model-preview"><el-tag v-for="model in models.slice(0, 4)" :key="model.service_type + model.id" effect="plain">{{ model.id }}</el-tag></div><p v-else class="muted">暂无可用模型目录，或目录暂时无法加载。</p><el-button @click="$router.push('/models')">前往模型广场 →</el-button></el-card>
      <el-card><template #header><b>近 7 天使用状态</b></template><el-empty v-if="!usage?.summary.requests" description="暂无调用记录。创建 Key 后即可开始使用。" :image-size="100" /><p v-else>已产生 {{ usage.summary.requests }} 条账单记录，费用 ¥ {{ usage.summary.cost }}。</p><p class="muted">个人入口便于自助查看。校园账户当前仍拥有全局管理员权限，个人限额不是安全隔离。</p></el-card>
    </div>
  </section>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useUserStore } from '@/stores/user'
import { getBalance, getCatalog, getKeys, getUsage, type BalanceInfo, type CatalogModel, type KeyList, type UsageInfo } from '@/api/selfService'
const user = useUserStore().userInfo
const balance = ref<BalanceInfo>()
const keys = ref<KeyList>()
const usage = ref<UsageInfo>()
const models = ref<CatalogModel[]>([])
const loading = ref(true)
const error = ref('')
const format = (value?: number) => value == null ? '—' : Number(value).toLocaleString('zh-CN')
onMounted(async () => {
  const from = new Date(); from.setDate(from.getDate() - 6)
  const localDate = `${from.getFullYear()}-${String(from.getMonth() + 1).padStart(2, '0')}-${String(from.getDate()).padStart(2, '0')}`
  try { [balance.value, keys.value, usage.value] = await Promise.all([getBalance(), getKeys(), getUsage({ from: localDate, size: 5 })]) }
  catch { error.value = '暂时无法加载账户信息，请刷新或联系管理员。' }
  finally { loading.value = false }
  try { models.value = await getCatalog() } catch { /* Model directory has its own retry page. */ }
})
</script>

<style scoped>.model-preview{display:flex;flex-wrap:wrap;gap:8px;margin:0 0 18px}</style>
