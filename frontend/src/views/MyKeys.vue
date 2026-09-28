<template>
  <section class="page-shell">
    <div class="page-heading"><div><p class="eyebrow">CREDENTIALS</p><h1>我的 API Key</h1><p>为应用创建独立密钥。明文仅在创建或轮换时展示一次。</p></div><el-button type="primary" :disabled="!!data && data.count >= data.limit" @click="showCreate = true">新建 Key</el-button></div>
    <el-alert v-if="error" type="error" :title="error" show-icon class="gap" />
    <el-alert title="停用不会释放名额；撤销不可恢复且立即释放名额。全局管理员仍可修改上限或绕过上限。" type="info" show-icon class="gap" />
    <el-card v-loading="loading"><template #header><b>已用 {{ data?.count ?? '—' }} / {{ data?.limit ?? '—' }} 个名额</b></template>
      <el-table :data="data?.keys || []" style="width:100%" empty-text="还没有 Key，点击新建开始使用">
        <el-table-column prop="name" label="名称" min-width="150" /><el-table-column prop="keyId" label="Key ID" min-width="240" /><el-table-column label="状态" width="105"><template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="175" /><el-table-column label="操作" min-width="225"><template #default="{ row }"><el-button link @click="toggle(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button><el-button link @click="rotate(row)">轮换</el-button><el-button link type="danger" @click="revoke(row)">撤销</el-button></template></el-table-column>
      </el-table>
    </el-card>
    <el-dialog v-model="showCreate" title="新建 API Key" width="min(440px, 94vw)"><el-input v-model="name" maxlength="120" show-word-limit placeholder="如：课程项目 / 个人开发" /><template #footer><el-button @click="showCreate = false">取消</el-button><el-button type="primary" :loading="busy" @click="create">创建</el-button></template></el-dialog>
    <el-dialog v-model="showSecret" title="请立即保存您的 Key" @closed="secret = ''" width="min(580px, 94vw)" :close-on-click-modal="false"><el-alert title="关闭后无法再次查看；请勿通过聊天工具传播。" type="warning" show-icon :closable="false" /><el-input :model-value="secret" readonly class="secret-field"><template #append><el-button @click="copy">复制</el-button></template></el-input><template #footer><el-button type="primary" @click="showSecret = false; secret = ''">已安全保存</el-button></template></el-dialog>
  </section>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getKeys, createKey, rotateKey, changeKey, type CampusKey, type KeyList } from '@/api/selfService'
const data = ref<KeyList>()
const loading = ref(false), busy = ref(false), showCreate = ref(false), showSecret = ref(false)
const name = ref(''), secret = ref(''), error = ref('')
async function refresh() { loading.value = true; try { data.value = await getKeys(); error.value = '' } catch { error.value = '获取 Key 列表失败' } finally { loading.value = false } }
onMounted(refresh)
async function create() { if (!name.value.trim()) return ElMessage.warning('请输入名称'); busy.value = true; try { const issued = await createKey(name.value); secret.value = issued.secret; showCreate.value = false; showSecret.value = true; name.value = ''; await refresh() } catch (e: any) { ElMessage.error(e.response?.data?.detail || '创建失败，请检查名额') } finally { busy.value = false } }
async function rotate(row: CampusKey) { try { await ElMessageBox.confirm(`轮换「${row.name}」将使旧密钥立即失效，是否继续？`, '确认轮换', { type: 'warning' }); const issued = await rotateKey(row.keyId); secret.value = issued.secret; showSecret.value = true; await refresh() } catch (e: any) { if (e !== 'cancel') ElMessage.error('轮换失败') } }
async function toggle(row: CampusKey) { try { await ElMessageBox.confirm(`${row.status === 'ACTIVE' ? '停用' : '启用'}「${row.name}」？`, '确认操作'); await changeKey(row.keyId, row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'); await refresh() } catch (e: any) { if (e !== 'cancel') ElMessage.error('操作失败') } }
async function revoke(row: CampusKey) { try { await ElMessageBox.confirm(`永久撤销「${row.name}」？此操作不可恢复，客户端将立即无法调用。`, '危险操作', { type: 'error', confirmButtonText: '撤销' }); await changeKey(row.keyId, 'REVOKED'); await refresh() } catch (e: any) { if (e !== 'cancel') ElMessage.error('撤销失败') } }
async function copy() { try { await navigator.clipboard.writeText(secret.value); ElMessage.success('已复制') } catch { ElMessage.error('复制失败，请手动选择') } }
</script>
<style scoped>.secret-field{margin-top:18px}</style>
