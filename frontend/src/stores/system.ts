// 文件说明：system：前端模块，集中维护相关类型、状态或交互逻辑。
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { DashboardOverview } from '@/types'

export const useSystemStore = defineStore('system', () => {
  const dashboardData = ref<DashboardOverview | null>(null)
  const loading = ref(false)

  const setDashboardData = (data: DashboardOverview) => {
    dashboardData.value = data
  }

  const setLoading = (state: boolean) => {
    loading.value = state
  }

  return {
    dashboardData,
    loading,
    setDashboardData,
    setLoading
  }
})