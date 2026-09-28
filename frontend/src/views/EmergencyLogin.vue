<!-- 文件说明：EmergencyLogin：管理端页面或组件，维护交互与视图状态。 -->
<template>
  <div class="login-container">
    <el-card class="login-card">
      <h2>管理员应急登录</h2>
      <el-alert title="仅用于 CAS 故障处置，并受后端开关、可信网段、限流与审计控制。" type="warning" :closable="false" />
      <el-form :model="form" @submit.prevent="submit">
        <el-form-item label="用户名"><el-input v-model="form.username" autocomplete="username" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="form.password" type="password" show-password autocomplete="current-password" /></el-form-item>
        <el-button type="danger" native-type="submit" :loading="loading" class="full">应急登录</el-button>
      </el-form>
    </el-card>
  </div>
</template>
<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
const form = reactive({ username: '', password: '' })
const loading = ref(false)
const router = useRouter()
const store = useUserStore()
async function submit() {
  loading.value = true
  try { await store.emergencyLogin(form.username, form.password); await router.replace('/dashboard/main') }
  catch (error: any) { ElMessage.error(error?.message || '应急登录失败') }
  finally { loading.value = false }
}
</script>
<style scoped>.login-container{min-height:100vh;display:grid;place-items:center;background:#f5f7fa}.login-card{width:min(420px,100%)}.el-alert{margin:16px 0}.full{width:100%}</style>
