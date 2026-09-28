<!-- 文件说明：TeacherPortal：管理端页面或组件，维护交互与视图状态。 -->
<template>
  <div class="teacher-page">
    <el-card>
      <template #header><b>教师门户</b></template>
      <p>{{ store.userInfo?.displayName }}（{{ store.userInfo?.account }}）</p>
      <el-form inline @submit.prevent="bindDevice">
        <el-form-item label="设备名称"><el-input v-model="deviceName" placeholder="例如：办公室电脑" /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading">绑定当前设备并获取令牌</el-button>
      </el-form>
    </el-card>
    <el-card v-if="profile" class="section">
      <template #header><b>专属模型访问</b></template>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="Base URL"><code>{{ profile.baseUrl }}</code></el-descriptions-item>
        <el-descriptions-item label="短期令牌"><el-input :model-value="profile.accessToken" readonly show-password /></el-descriptions-item>
        <el-descriptions-item label="过期时间">{{ profile.expiresAt }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
    <el-card class="section">
      <template #header><b>已绑定设备</b></template>
      <el-table :data="devices">
        <el-table-column prop="deviceName" label="设备" /><el-table-column prop="deviceId" label="设备 ID" />
        <el-table-column prop="status" label="状态" /><el-table-column prop="lastSeenAt" label="最近使用" />
        <el-table-column label="操作"><template #default="scope"><el-button type="danger" link @click="revoke(scope.row.deviceId)">撤销</el-button></template></el-table-column>
      </el-table>
    </el-card>
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import type { RouterResponse } from '@/types'
import { useUserStore } from '@/stores/user'
interface AccessProfile { baseUrl:string; accessToken:string; expiresAt:string; credentialId:string; deviceId:string }
interface Device { deviceId:string; deviceName?:string; status:string; lastSeenAt?:string }
const store=useUserStore(); const loading=ref(false); const devices=ref<Device[]>([]); const profile=ref<AccessProfile|null>(null)
const deviceName=ref(navigator.userAgent.includes('Windows')?'Windows 设备':'浏览器设备')
const key='campus_teacher_device_id'; let deviceId=localStorage.getItem(key)
if(!deviceId){ deviceId=crypto.randomUUID(); localStorage.setItem(key,deviceId) }
async function load(){ const r=await request.get<RouterResponse<Device[]>>('/teacher/devices'); devices.value=r.data.data??[] }
async function bindDevice(){ loading.value=true; try { const r=await request.post<RouterResponse<AccessProfile>>('/teacher/devices/bind',{deviceId,deviceName:deviceName.value}); profile.value=r.data.data??null; await load(); ElMessage.success('设备绑定成功') } catch(e:any){ ElMessage.error(e?.response?.data?.message||'设备绑定失败') } finally{loading.value=false} }
async function revoke(id:string){ await request.delete(`/teacher/devices/${encodeURIComponent(id)}`); if(id===deviceId) profile.value=null; await load() }
onMounted(load)
</script>
<style scoped>.teacher-page{display:grid;gap:16px}.section{margin-top:0}code{word-break:break-all}</style>
