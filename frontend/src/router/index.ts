// 文件说明：index：前端模块，集中维护相关类型、状态或交互逻辑。
import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { title } from 'process'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/Login.vue'),
      meta: { requiresAuth: false }
    },
    {
      path: '/login/emergency',
      name: 'emergency-login',
      component: () => import('../views/EmergencyLogin.vue'),
      meta: { requiresAuth: false }
    },
    {
      path: '/auth/callback',
      name: 'auth-callback',
      component: () => import('../views/AuthCallback.vue'),
      meta: { requiresAuth: false }
    },
    {
      path: '/teacher',
      name: 'teacher-portal',
      component: () => import('../views/Layout.vue'),
      meta: { requiresAuth: true, roles: ['TEACHER'] },
      children: [{ path: '', component: () => import('../views/TeacherPortal.vue'), meta: { title: '教师门户' } }]
    },
    {
      path: '/profile',
      name: 'profile',
      component: () => import('../views/Layout.vue'),
      meta: { requiresAuth: true, roles: ['ADMIN', 'TEACHER', 'STUDENT', 'USER'] },
      children: [{ path: '', component: () => import('../views/Profile.vue'), meta: { title: '个人信息' } }]
    },
    {
      path: '/',
      name: 'home',
      component: () => import('../views/Layout.vue'),
      redirect: '/me',
      meta: { requiresAuth: true },
      children: []
    },
    {
      path: '/me', component: () => import('../views/Layout.vue'),
      meta: { requiresAuth: true },
      children: [
        { path: '', component: () => import('../views/MyWorkspace.vue'), meta: { title: '我的工作台' } },
        { path: 'keys', component: () => import('../views/MyKeys.vue'), meta: { title: '我的 Key' } },
        { path: 'usage', component: () => import('../views/MyUsage.vue'), meta: { title: '我的用量' } },
        { path: 'models', redirect: '/models' }
      ]
    },
    {
      path: '/models', component: () => import('../views/Layout.vue'), meta: { requiresAuth: true },
      children: [{ path: '', component: () => import('../views/ModelSquare.vue'), meta: { title: '模型广场' } }]
    },
    {
      path: '/system/model-square', component: () => import('../views/Layout.vue'), meta: { requiresAuth: true, roles: ['ADMIN'] },
      children: [{ path: '', component: () => import('../views/ModelSquareContent.vue'), meta: { title: '模型广场内容' } }]
    },
    // 概览
    {
      path: '/dashboard',
      name: 'dashboard',
      component: () => import('../views/Layout.vue'),
      redirect: '/dashboard/main',
      meta: { requiresAuth: true, roles: ['ADMIN'] },
      children: [
        {
          path: 'main',
          name: 'dashboard-main',
          component: () => import('../views/Dashboard.vue'),
          meta: { title: '仪表板', icon: 'house' }
        }
      ]
    },
    // 配置管理
    {
      path: '/config',
      name: 'config',
      component: () => import('../views/Layout.vue'),
      meta: { requiresAuth: true, roles: ['ADMIN'] },
      children: [
        {
          path: 'services',
          name: 'service-management',
          component: () => import('../views/config/ServiceManagement.vue'),
          meta: { title: '服务管理', icon: 'setting' }
        },
        {
          path: 'instances',
          name: 'instance-management',
          component: () => import('../views/config/InstanceManagement.vue'),
          meta: { title: '实例管理', icon: 'cpu' }
        },
        {
          path: 'versions',
          name: 'version-management',
          component: () => import('../views/config/VersionManagement.vue'),
          meta: { title: '版本管理', icon: 'document' }
        },
        {
          path: 'circuit-breakers',
          name: 'circuit-breaker-config',
          component: () => import('../views/config/CircuitBreakerManagement.vue'),
          meta: { title: '熔断器配置', icon: 'bolt' }
        },
        {
          path: 'load-balancers',
          name: 'load-balancer-config',
          component: () => import('../views/config/LoadBalancerManagement.vue'),
          meta: { title: '负载均衡器配置', icon: 'connection' }
        },
        {
          path: 'state-persistence',
          name: 'state-persistence-config',
          component: () => import('../views/config/StatePersistenceManagement.vue'),
          meta: { title: '状态持久化', icon: 'folder-opened' }
        }
      ]
    },
    // 安全管理
    {
      path: '/security',
      name: 'security',
      component: () => import('../views/Layout.vue'),
      meta: { requiresAuth: true, roles: ['ADMIN'] },
      children: [
        {
          path: 'api-keys',
          name: 'api-key-management',
          component: () => import('../views/security/CampusKeyAdmin.vue'),
          meta: { title: 'API密钥管理', icon: 'key' }
        },
        {
          path: 'jwt-tokens',
          name: 'jwt-token-management',
          component: () => import('../views/security/JwtTokenManagement.vue'),
          meta: { title: 'JWT令牌管理', icon: 'lock' }
        },
        {
          path: 'blacklist',
          name: 'blacklist-management',
          component: () => import('../views/security/BlacklistManagement.vue'),
          meta: { title: '安全黑名单', icon: 'warning' }
        },
        {
          path: 'audit-logs',
          name: 'audit-log-management',
          component: () => import('../views/security/AuditLogManagement.vue'),
          meta: { title: '审计日志', icon: 'document-checked' }
        }
      ]
    },
    // 系统管理
    {
      path: '/system',
      name: 'system',
      component: () => import('../views/Layout.vue'),
      meta: { requiresAuth: true, roles: ['ADMIN'] },
      children: [
        {
          path: 'accounts',
          name: 'account-management',
          component: () => import('../views/security/JwtAccountManagement.vue'),
          meta: { title: '账户管理', icon: 'user' }
        },
        {
          path: 'pricing',
          name: 'pricing-management',
          component: () => import('@/views/PricingManagement.vue'),
          meta: { title: '模型定价管理' }
        },
        {
          path: 'billing-dimensions',
          name: 'billing-dimensions',
          component: () => import('@/views/DimensionManagement.vue'),
          meta: { title: '计费维度管理' }
        }
      ]
    },
    // 追踪管理 - 重构后的结构
    {
      path: '/tracing',
      name: 'tracing',
      component: () => import('../views/Layout.vue'),
      redirect: '/tracing/dashboard',
      meta: { requiresAuth: true, roles: ['ADMIN'] },
      children: [
        {
          path: 'dashboard',
          name: 'tracing-dashboard',
          component: () => import('../views/tracing/Dashboard.vue'),
          meta: { title: '追踪仪表盘', icon: 'connection' }
        },
        {
          path: 'search',
          name: 'tracing-search',
          component: () => import('../views/tracing/Search.vue'),
          meta: { title: '链路追踪', icon: 'search' }
        },
        {
          path: 'management',
          name: 'tracing-management',
          component: () => import('../views/tracing/Management.vue'),
          meta: { title: '追踪配置', icon: 'setting' }
        }
      ]
    },
    // 兼容旧路由
    {
      path: '/tracing/overview',
      redirect: '/tracing/dashboard'
    },
    {
      path: '/tracing/performance',
      redirect: '/tracing/dashboard'
    },
    // 兼容 /admin/admin/tracing 的错误路径
    {
      path: '/admin/tracing/:pathMatch(.*)*',
      redirect: to => `/tracing/${to.params.pathMatch || 'dashboard'}`
    },
    // AI 试验场 - 各服务作为独立子路由
    {
      path: '/playground',
      name: 'playground',
      component: () => import('../views/Layout.vue'),
      redirect: '/playground/chat',
      meta: { requiresAuth: true, roles: ['ADMIN', 'TEACHER', 'STUDENT', 'USER'] },
      children: [
        {
          path: 'chat',
          name: 'playground-chat',
          component: () => import('../views/playground/components/chat/ChatContainer.vue'),
          meta: { title: '对话测试', icon: 'chat-dot-round' }
        },
        {
          path: 'embedding',
          name: 'playground-embedding',
          component: () => import('../views/playground/components/embedding/EmbeddingContainer.vue'),
          meta: { title: '向量生成', icon: 'data-line' }
        },
        {
          path: 'rerank',
          name: 'playground-rerank',
          component: () => import('../views/playground/components/rerank/RerankContainer.vue'),
          meta: { title: '重排序', icon: 'sort' }
        },
        {
          path: 'audio',
          name: 'playground-audio',
          component: () => import('../views/playground/components/audio/AudioContainer.vue'),
          meta: { title: '语音服务', icon: 'headset' }
        },
        {
          path: 'image',
          name: 'playground-image',
          component: () => import('../views/playground/components/image/ImageContainer.vue'),
          meta: { title: '图像服务', icon: 'picture' }
        }
      ]
    },
    // 兼容旧路径
    {
      path: '/playground/main',
      redirect: '/playground/chat'
    },
    // 兼容 /admin/playground 路径
    {
      path: '/admin/playground/:pathMatch(.*)*',
      redirect: '/playground/chat'
    },
    // 异常管理
    {
      path: '/exceptions',
      name: 'exceptions',
      component: () => import('../views/Layout.vue'),
      redirect: '/exceptions/list',
      meta: { requiresAuth: true, roles: ['ADMIN'] },
      children: [
        {
          path: 'list',
          name: 'exception-list',
          component: () => import('../views/exception/ExceptionManagement.vue'),
          meta: { title: '异常事件管理', icon: 'warning' }
        },
        {
          path: 'detail/:id',
          name: 'exception-detail',
          component: () => import('../views/exception/ExceptionDetail.vue'),
          meta: { title: '异常事件详情', icon: 'document-checked' }
        },
        {
          path: 'statistics',
          name: 'exception-statistics',
          component: () => import('../views/exception/ExceptionStatistics.vue'),
          meta: { title: '异常统计分析', icon: 'data-analysis' }
        }
      ]
    },
    // Token 使用量统计路由
    {
      path: '/token-usage',
      name: 'token-usage',
      component: () => import('../views/Layout.vue'),
      redirect: '/token-usage/statistics',
      meta: { requiresAuth: true, roles: ['ADMIN'] },
      children: [
        {
          path: 'statistics',
          name: 'token-usage-statistics',
          component: () => import('../views/tokenUsage/TokenUsageStatistics.vue'),
          meta: { title: 'Token 使用统计', icon: 'data-analysis' }
        }
      ]
    }
  ]
})

// Session-first route guard. The emergency JWT is only a fallback handled by the same store.
router.beforeEach(async to => {
  const userStore = useUserStore()
  const publicRoute = to.matched.every(record => record.meta.requiresAuth === false)

  if (!userStore.sessionChecked) await userStore.restoreSession()

  if (publicRoute) {
    if (userStore.isAuthenticated && to.name === 'login') return userStore.landingPath()
    return true
  }
  if (!userStore.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  const requiredRoles = to.matched.flatMap(record => (record.meta.roles as string[] | undefined) ?? [])
  if (requiredRoles.length > 0 && !requiredRoles.some(role => userStore.hasRole(role))) {
    return userStore.landingPath()
  }
  return true
})

export default router
