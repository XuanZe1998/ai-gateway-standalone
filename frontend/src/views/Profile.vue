<!-- 文件说明：Profile：管理端页面或组件，维护交互与视图状态。 -->
<template>
  <section class="page-shell"><div class="page-heading"><div><p class="eyebrow">ACCOUNT</p><h1>个人资料</h1><p>账户来自校园统一身份认证，不在网关内修改。</p></div></div><el-card>
    <template #header><b>个人信息</b></template>
    <el-descriptions :column="1" border>
      <el-descriptions-item label="姓名">{{ user?.displayName }}</el-descriptions-item>
      <el-descriptions-item label="账号">{{ user?.account }}</el-descriptions-item>
      <el-descriptions-item label="部门">{{ user?.departmentName || '—' }}</el-descriptions-item>
      <el-descriptions-item label="身份">{{ user?.typeName || user?.typeCode || '—' }}</el-descriptions-item>
      <el-descriptions-item label="校园用户 ID">{{ profile?.ownerId ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="计费用户 ID">{{ profile?.platformUserId ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="实名认证"><el-tag :type="profile?.verified ? 'success' : 'warning'">{{ profile?.verified ? '已完成' : '未完成' }}</el-tag></el-descriptions-item>
      <el-descriptions-item label="角色">
        <el-tag v-for="role in user?.roles" :key="role" class="tag">{{ role }}</el-tag>
      </el-descriptions-item>
    </el-descriptions>
  </el-card></section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import request from '@/utils/request'
import { useUserStore } from '@/stores/user'

const store = useUserStore()
const user = computed(() => store.userInfo)
const profile = ref<{ ownerId: number; platformUserId: string; verified: boolean }>()
onMounted(async () => { try { profile.value = (await request.get<typeof profile.value>('/me/profile')).data } catch { /* Emergency admin has no campus profile. */ } })
</script>

<style scoped>
.tag {
  margin-right: 8px;
}
</style>
