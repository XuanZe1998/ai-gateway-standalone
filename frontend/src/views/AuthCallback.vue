<!-- 文件说明：AuthCallback：管理端页面或组件，维护交互与视图状态。 -->
<template>
  <div class="callback-page">
    <el-result :icon="failed ? 'error' : 'info'" :title="failed ? '登录失败' : '正在恢复登录会话'" :sub-title="message">
      <template #extra><el-button v-if="failed" type="primary" @click="$router.replace('/login')">重新登录</el-button></template>
    </el-result>
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
const router = useRouter()
const userStore = useUserStore()
const failed = ref(false)
const message = ref('请稍候…')
onMounted(async () => {
  try {
    const user = await userStore.restoreSession(true)
    if (!user) throw new Error('学校统一认证会话不存在或已过期')
    await router.replace(userStore.landingPath())
  } catch (error: any) {
    failed.value = true
    message.value = error?.response?.data?.message || error?.message || '无法恢复登录会话'
  }
})
</script>
<style scoped>.callback-page { min-height: 100vh; display: grid; place-items: center; }</style>
