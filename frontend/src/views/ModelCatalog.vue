<template>
  <section class="page-shell"><div class="page-heading"><div><p class="eyebrow">MODEL CATALOG</p><h1>模型目录与接入</h1><p>仅展示可用模型与服务类型，不包含上游实例地址或凭据。</p></div><el-button @click="load">刷新目录</el-button></div>
    <el-card class="gap"><template #header><b>调用地址与示例</b></template><p>API 地址：<code>{{ baseUrl }}</code></p><p class="muted">把示例中的 <code>YOUR_KEY</code> 替换为您在「我的 Key」创建的密钥。真实调用遵循实名认证、免费额度和付费余额规则。</p><pre class="sample">curl -X POST "{{ baseUrl }}/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: YOUR_KEY" \
  -d '{"model":"{{ models.find(m => m.service_type === 'chat')?.id || 'MODEL_ID' }}","messages":[{"role":"user","content":"你好"}]}'</pre><el-button @click="copy">复制示例</el-button></el-card>
    <el-alert v-if="error" :title="error" type="error" show-icon class="gap" />
    <el-card v-loading="loading"><template #header><b>可用模型（{{ models.length }}）</b></template><el-table :data="models" empty-text="暂无可用模型，请联系管理员检查服务配置"><el-table-column prop="id" label="模型 ID" min-width="230" /><el-table-column label="服务能力" min-width="170"><template #default="{ row }"><el-tag>{{ labels[row.service_type] || row.service_type }}</el-tag></template></el-table-column></el-table></el-card>
  </section>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getCatalog, type CatalogModel } from '@/api/selfService'
const baseUrl = window.location.origin
const models = ref<CatalogModel[]>([]), error = ref(''), loading = ref(false)
const labels: Record<string, string> = { chat: '对话', embedding: '向量生成', rerank: '重排序', tts: '语音合成', stt: '语音识别', imgGen: '图像生成', imgEdit: '图像编辑', vidGen: '视频生成' }
const sample = () => `curl -X POST "${baseUrl}/v1/chat/completions" -H "Content-Type: application/json" -H "X-API-Key: YOUR_KEY" -d '{"model":"${models.value.find(m => m.service_type === 'chat')?.id || 'MODEL_ID'}","messages":[{"role":"user","content":"你好"}]}'`
async function copy() { try { await navigator.clipboard.writeText(sample()); ElMessage.success('已复制') } catch { ElMessage.error('复制失败') } }
async function load() { loading.value = true; try { models.value = await getCatalog(); error.value = '' } catch { error.value = '加载模型目录失败' } finally { loading.value = false } }
onMounted(load)
</script>
<style scoped>.sample{padding:20px;border-radius:12px;background:#10243d;color:#e4f7fa;overflow:auto;line-height:1.8}</style>
