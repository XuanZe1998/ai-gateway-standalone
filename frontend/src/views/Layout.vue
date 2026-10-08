<!-- 文件说明：Layout：管理端页面或组件，维护交互与视图状态。 -->
<template>
  <el-container class="layout-container">
    <div v-if="mobileMenuOpen" class="navigation-backdrop" @click="mobileMenuOpen = false" />
    <el-aside width="240px" class="navigation" :class="{ 'navigation-open': mobileMenuOpen }">
      <el-menu :default-active="route.path" :default-openeds="defaultOpeneds" router class="layout-menu">
        <div class="logo"><el-icon><Cpu /></el-icon><span>AI 算力平台</span></div>

        <div class="nav-label">我的工作台</div>
        <el-menu-item index="/me">工作台概览</el-menu-item>
        <el-menu-item index="/me/keys">我的 Key</el-menu-item>
        <el-menu-item index="/me/usage">我的用量</el-menu-item>
        <el-menu-item index="/profile">个人资料</el-menu-item>
        <div class="nav-label">模型与接入</div>
        <el-menu-item index="/models"><el-icon><Cpu /></el-icon><span>模型广场</span></el-menu-item>
        <div class="nav-label">管理控制台 · 全局权限</div>
        <template v-if="isAdmin">
          <el-sub-menu index="dashboard"><template #title><el-icon><House /></el-icon><span>概览</span></template><el-menu-item index="/dashboard/main">仪表板</el-menu-item></el-sub-menu>
          <el-sub-menu index="config"><template #title><el-icon><Setting /></el-icon><span>配置管理</span></template>
            <el-menu-item index="/config/services">服务管理</el-menu-item><el-menu-item index="/config/instances">实例管理</el-menu-item><el-menu-item index="/config/versions">版本管理</el-menu-item><el-menu-item index="/config/circuit-breakers">熔断器配置</el-menu-item><el-menu-item index="/config/load-balancers">负载均衡器配置</el-menu-item><el-menu-item index="/config/state-persistence">状态持久化</el-menu-item>
          </el-sub-menu>
          <el-sub-menu index="security"><template #title><el-icon><Lock /></el-icon><span>安全管理</span></template>
            <el-menu-item index="/security/api-keys">API 密钥管理</el-menu-item><el-menu-item index="/security/jwt-tokens">JWT 令牌管理</el-menu-item><el-menu-item index="/security/blacklist">安全黑名单</el-menu-item><el-menu-item index="/security/audit-logs">审计日志</el-menu-item>
          </el-sub-menu>
          <el-menu-item index="/system/model-square"><el-icon><Reading /></el-icon><span>模型广场内容</span></el-menu-item>
          <el-menu-item index="/system/pricing"><el-icon><Coin /></el-icon><span>模型定价管理</span></el-menu-item>
          <el-menu-item index="/system/billing-dimensions"><el-icon><Coin /></el-icon><span>计费维度管理</span></el-menu-item>
          <el-menu-item index="/system/accounts"><el-icon><User /></el-icon><span>账户管理</span></el-menu-item>
          <el-menu-item index="/exceptions/list"><el-icon><Warning /></el-icon><span>异常管理</span></el-menu-item>
          <el-menu-item index="/token-usage/statistics"><el-icon><DataAnalysis /></el-icon><span>Token 统计</span></el-menu-item>
          <el-menu-item index="/tracing/dashboard"><el-icon><Connection /></el-icon><span>链路追踪</span></el-menu-item>
        </template>

        <div class="nav-label">AI 试验场</div>
        <el-menu-item v-if="isTeacher" index="/teacher"><el-icon><Reading /></el-icon><span>教师门户</span></el-menu-item>
        <el-sub-menu index="playground"><template #title><el-icon><Monitor /></el-icon><span>AI 试验场</span></template>
          <el-menu-item index="/playground/chat">对话测试</el-menu-item><el-menu-item index="/playground/embedding">向量生成</el-menu-item><el-menu-item index="/playground/rerank">重排序</el-menu-item><el-menu-item index="/playground/audio">语音服务</el-menu-item><el-menu-item index="/playground/image">图像服务</el-menu-item>
        </el-sub-menu>

      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="layout-header">
        <el-button class="mobile-menu-button" text aria-label="打开导航" @click="mobileMenuOpen = !mobileMenuOpen"><el-icon><Menu /></el-icon></el-button>
        <el-breadcrumb separator="/"><el-breadcrumb-item>首页</el-breadcrumb-item><el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item></el-breadcrumb>
        <el-dropdown @command="handleUserCommand"><span class="user-info"><el-avatar :size="30" :icon="UserFilled" /><span>{{ displayName }}</span></span>
          <template #dropdown><el-dropdown-menu><el-dropdown-item command="profile">个人资料</el-dropdown-item><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu></template>
        </el-dropdown>
      </el-header>
      <el-main class="layout-main"><router-view :key="route.fullPath" /></el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Coin, Connection, Cpu, DataAnalysis, House, Lock, Menu, Monitor, Reading, Setting, User, UserFilled, Warning } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
const route=useRoute(); const router=useRouter(); const userStore=useUserStore()
const mobileMenuOpen=ref(false)
watch(() => route.fullPath, () => { mobileMenuOpen.value = false })
const isAdmin=computed(()=>userStore.hasRole('ADMIN')); const isTeacher=computed(()=>userStore.hasRole('TEACHER'))
const displayName=computed(()=>userStore.userInfo?.displayName||userStore.userInfo?.account||'用户')
const currentTitle=computed(()=>String(route.meta.title||'控制台'))
const defaultOpeneds=computed(()=>[route.path.split('/')[1]].filter(Boolean))
async function handleUserCommand(command:string){
 if(command==='profile'){await router.push('/profile');return}
 if(command==='logout'){const url=await userStore.logout(); if(url) window.location.assign(url); else await router.replace('/login')}
}
</script>

<style scoped>
.layout-container{height:100vh;background:#f5f7fa}
.layout-menu{height:100vh;background:linear-gradient(150deg,#26384a,#34495e);border:0}
.layout-menu :deep(.el-menu-item),.layout-menu :deep(.el-sub-menu__title){color:#dfe8f1}
.layout-menu :deep(.is-active){color:#409eff!important;background:rgba(64,158,255,.12)}
.logo{height:72px;display:flex;align-items:center;justify-content:center;gap:10px;color:#fff;font-size:19px;font-weight:600}
.layout-header{display:flex;align-items:center;justify-content:space-between;background:#fff;border-bottom:1px solid #e6e8eb}
.user-info{display:flex;align-items:center;gap:10px;cursor:pointer}
.layout-main{padding:28px;overflow:auto;min-width:0}
.nav-label{padding:21px 21px 8px;color:#7fa9c3;font-size:11px;font-weight:700;letter-spacing:.11em}
.layout-menu{overflow-y:auto;overflow-x:hidden}
.navigation{background:#10243d;overflow:auto}
.layout-container{min-height:100vh;height:100vh}
.layout-header{height:68px;box-shadow:0 1px 12px #1233540d}
.logo{justify-content:flex-start;padding-left:23px}
.mobile-menu-button{display:none}
.layout-menu :deep(.el-menu-item:hover),
.layout-menu :deep(.el-sub-menu__title:hover){color:#fff;background:rgba(255,255,255,.10)}
.layout-menu :deep(.el-menu-item.is-active:hover){color:#409eff;background:rgba(64,158,255,.22)}
.layout-menu :deep(.el-menu--inline){background:rgba(255,255,255,.04)}


@media(max-width:800px){
  .navigation{position:fixed;left:0;top:0;bottom:0;z-index:102;transform:translateX(-100%);transition:transform .2s ease;width:240px!important;box-shadow:10px 0 28px #0f314055}
  .navigation.navigation-open{transform:translateX(0)}
  .navigation-backdrop{position:fixed;inset:0;z-index:101;background:#09243988}
  .mobile-menu-button{display:inline-flex;margin-right:5px}
  .layout-main{padding:16px}.layout-header{padding:0 16px;justify-content:flex-start;gap:10px}
  .layout-header .user-info{margin-left:auto}.layout-header .el-dropdown{margin-left:auto}
}
</style>
