// 文件说明：user：前端模块，集中维护相关类型、状态或交互逻辑。
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import request from '@/utils/request'
import type { RouterResponse } from '@/types'

export type Portal = 'ADMIN' | 'TEACHER' | 'PLAYGROUND' | 'PROFILE'

export interface CsrfInfo {
  headerName: string
  token?: string | null
}

export interface UserInfo {
  account: string
  displayName: string
  departmentName?: string | null
  typeCode?: string | null
  typeName?: string | null
  roles: string[]
  permissions: string[]
  portals: Portal[]
  csrf?: CsrfInfo
}

interface LoginResponseData {
  token: string
  tokenType: string
  expiresIn: number
}

interface LogoutData {
  logoutUrl: string
}

export const useUserStore = defineStore('user', () => {
  // localStorage is used only for the hidden emergency administrator JWT.
  const token = ref<string | null>(localStorage.getItem('admin_token'))
  const userInfo = ref<UserInfo | null>(null)
  const sessionChecked = ref(false)
  let refreshTimer: number | null = null

  const roles = computed(() => userInfo.value?.roles ?? [])
  const isAuthenticated = computed(() => Boolean(userInfo.value || token.value))
  const hasRole = (role: string) => roles.value.includes(role)

  const setToken = (value: string) => {
    token.value = value
    localStorage.setItem('admin_token', value)
  }

  const clearAuth = () => {
    token.value = null
    userInfo.value = null
    sessionChecked.value = false
    localStorage.removeItem('admin_token')
    if (refreshTimer !== null) {
      window.clearInterval(refreshTimer)
      refreshTimer = null
    }
  }

  const restoreSession = async (force = false): Promise<UserInfo | null> => {
    if (sessionChecked.value && !force) return userInfo.value
    try {
      const response = await request.get<RouterResponse<UserInfo>>('/auth/session', {
        skipAuthRedirect: true
      })
      userInfo.value = response.data.data ?? null
      sessionChecked.value = true
      return userInfo.value
    } catch (error) {
      sessionChecked.value = true
      userInfo.value = null
      if (!token.value) return null
      try {
        const response = await request.post<RouterResponse<any>>('/auth/jwt/validate', {
          token: token.value
        }, { skipAuthRedirect: true })
        const raw = response.data.data?.user ?? response.data.data
        const emergencyRoles = Array.isArray(raw?.roles) ? raw.roles : ['ADMIN']
        userInfo.value = {
          account: raw?.username ?? raw?.account ?? 'admin',
          displayName: raw?.username ?? raw?.displayName ?? '应急管理员',
          roles: emergencyRoles.map((role: string) => role.replace(/^ROLE_/, '')),
          permissions: raw?.permissions ?? [],
          portals: ['ADMIN', 'PLAYGROUND', 'PROFILE']
        }
        return userInfo.value
      } catch {
        clearAuth()
        sessionChecked.value = true
        return null
      }
    }
  }

  const startTokenRefresh = () => {
    if (refreshTimer !== null) window.clearInterval(refreshTimer)
    refreshTimer = window.setInterval(async () => {
      if (!token.value) return
      try {
        await refreshToken()
      } catch {
        clearAuth()
        const { default: router } = await import('@/router')
        await router.push('/login/emergency')
      }
    }, 30 * 60 * 1000)
  }

  const refreshToken = async () => {
    const response = await request.post<RouterResponse<LoginResponseData>>('/auth/jwt/refresh', {
      token: token.value
    })
    if (!response.data.success || !response.data.data) {
      throw new Error(response.data.message || '令牌刷新失败')
    }
    setToken(response.data.data.token)
    return response.data
  }

  const emergencyLogin = async (username: string, password: string) => {
    const response = await request.post<RouterResponse<LoginResponseData>>('/auth/jwt/login', {
      username,
      password
    })
    if (!response.data.success || !response.data.data) {
      throw new Error(response.data.message || '登录失败')
    }
    setToken(response.data.data.token)
    sessionChecked.value = false
    startTokenRefresh()
    await restoreSession(true)
    return response.data
  }

  const logout = async (): Promise<string | null> => {
    let casLogoutUrl: string | null = null
    try {
      if (token.value) {
        await request.post('/auth/jwt/revoke', {
          token: token.value,
          userId: userInfo.value?.account ?? 'admin',
          reason: '用户主动登出'
        })
      } else if (userInfo.value) {
        const response = await request.post<RouterResponse<LogoutData>>('/auth/cas/logout')
        casLogoutUrl = response.data.data?.logoutUrl ?? null
      }
    } finally {
      clearAuth()
    }
    return casLogoutUrl
  }

  const landingPath = () => {
    if (hasRole('ADMIN')) return '/me'
    if (hasRole('TEACHER')) return '/teacher'
    return '/playground/chat'
  }

  return {
    token, userInfo, roles, sessionChecked, isAuthenticated,
    setToken, clearAuth, hasRole, restoreSession, emergencyLogin,
    login: emergencyLogin, logout, refreshToken, startTokenRefresh, landingPath
  }
})

