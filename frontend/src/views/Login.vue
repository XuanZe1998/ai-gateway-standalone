<!-- 文件说明：Login：管理端页面或组件，维护交互与视图状态。 -->
<template>
  <div class="login-container">
    <el-card class="login-card">
      <div class="login-header">
        <el-icon class="logo"><School /></el-icon>
        <h1>AI 算力平台</h1>
        <p>使用学校统一身份认证登录</p>
      </div>
      <el-button type="primary" size="large" class="login-button" :loading="loading" @click="login">
        学校统一身份认证
      </el-button>
      <p class="security-tip">登录凭据由学校 CAS 验证，本系统不会保存您的统一认证密码。</p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { School } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const loading = ref(false)
const router = useRouter()
const userStore = useUserStore()

function apiUrl(path: string) {
  const base = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')
  return `${base}${path}`
}

function login() {
  loading.value = true
  window.location.assign(apiUrl('/auth/cas/login?target=' + encodeURIComponent('/admin/auth/callback')))
}

onMounted(async () => {
  const user = await userStore.restoreSession()
  if (user) await router.replace(userStore.landingPath())
})
</script>

<style scoped>
.login-container { min-height: 100vh; display: grid; place-items: center; padding: 24px; background: linear-gradient(135deg,#eef4ff,#f7f3ff); }
.login-card { width: min(420px,100%); padding: 28px; border: 0; border-radius: 16px; box-shadow: 0 18px 50px rgba(31,45,61,.14); }
.login-header { text-align: center; margin-bottom: 30px; }
.logo { font-size: 64px; color: #409eff; }
h1 { margin: 12px 0 8px; color: #303133; }
p { color: #606266; }
.login-button { width: 100%; }
.security-tip { margin: 20px 0 0; font-size: 12px; line-height: 1.6; text-align: center; }
</style>
