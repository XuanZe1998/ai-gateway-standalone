// 文件说明：request：前端模块，集中维护相关类型、状态或交互逻辑。
import axios, { type AxiosInstance, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import type { ApiResponse } from '@/types'

declare module 'axios' {
  interface AxiosRequestConfig {
    skipAuthRedirect?: boolean
  }

  interface InternalAxiosRequestConfig {
    skipAuthRedirect?: boolean
  }
}

const request: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 60000,
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  headers: { 'Content-Type': 'application/json' }
})

request.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = localStorage.getItem('admin_token')
  const isTokenEndpoint = Boolean(config.url?.includes('/auth/jwt/login')
    || config.url?.includes('/auth/jwt/refresh'))
  if (token && config.headers && !isTokenEndpoint) {
    config.headers['Jairouter_Token'] = token
  }
  return config
})

request.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => response,
  error => {
    const skipRedirect = Boolean(error.config?.skipAuthRedirect)
    if (error.response?.status === 401 && !skipRedirect) {
      const isPlaygroundRequest = error.config?.url?.includes('/v1/')
      if (!isPlaygroundRequest) {
        localStorage.removeItem('admin_token')
        import('@/router').then(({ default: router }) => router.push({ name: 'login' }))
      }
    }
    return Promise.reject(error)
  }
)

export default request
