import type { ClientType, MenuNode, RoleBrief, UserSummary } from '@simple-notes/api'
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { authApi, clearStoredToken, getStoredToken, storeToken } from '@/api'
import { rebuildMenuRoutes } from '@/router'

/**
 * 认证 / 身份 store（docs/auth-rbac.md §6.2）：
 *
 * - 凭证存 localStorage（key `sn.auth.admin`），请求由 api 层注入 Bearer；
 * - `me` 一次性拉取身份 + root + codes + roles + 服务端菜单树（可见菜单的唯一来源）；
 * - 菜单树 → 动态路由由路由层 `rebuildMenuRoutes` 处理，本 store 只持有数据。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref(getStoredToken() ?? '')
  const user = ref<UserSummary | null>(null)
  /** 超管短路标记（持有 SUPER_ADMIN 角色） */
  const root = ref(false)
  const entries = ref<string[]>([])
  const codes = ref<string[]>([])
  const roles = ref<RoleBrief[]>([])
  const menus = ref<MenuNode[]>([])
  /** me 是否已成功拉取（F5 直连深链时守卫等它完成后才放行） */
  const ready = ref(false)
  const loginLoading = ref(false)

  const displayName = computed(() => user.value?.nickname || user.value?.username || '')

  /** 按钮 / 操作级权限判断：`root || codes.includes(code)`（与后端 `@authz.can` 语义一致） */
  function can(code: string): boolean {
    return root.value || codes.value.includes(code)
  }

  /** 多码任一（与后端 `@authz.canAny` 对齐） */
  function canAny(...list: string[]): boolean {
    return list.some((code) => can(code))
  }

  async function login(username: string, password: string): Promise<void> {
    loginLoading.value = true
    try {
      const client: ClientType = 'ADMIN'
      const response = await authApi.login({ username, password, client })
      if (!response.token) {
        throw new Error('登录响应缺少凭证')
      }
      token.value = response.token
      storeToken(response.token)
    } finally {
      loginLoading.value = false
    }
  }

  async function fetchMe(): Promise<void> {
    const me = await authApi.me()
    user.value = me.user ?? null
    root.value = me.root ?? false
    entries.value = me.entries ?? []
    codes.value = me.codes ?? []
    roles.value = me.roles ?? []
    menus.value = me.menus ?? []
    ready.value = true
  }

  /** 退出登录（服务端撤销当前会话；失败也照常清理本地状态） */
  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch {
      // 会话已失效 / 网络异常：本地清理照常进行
    } finally {
      reset()
    }
  }

  /** 退出所有设备（撤销该用户全部会话，含当前） */
  async function logoutAll(): Promise<void> {
    await authApi.logoutAll()
    reset()
  }

  /** 清空本地状态（不发请求）：凭证 + 身份 + 动态路由 */
  function reset(): void {
    clearStoredToken()
    rebuildMenuRoutes([])
    token.value = ''
    user.value = null
    root.value = false
    entries.value = []
    codes.value = []
    roles.value = []
    menus.value = []
    ready.value = false
  }

  return {
    token,
    user,
    root,
    entries,
    codes,
    roles,
    menus,
    ready,
    loginLoading,
    displayName,
    can,
    canAny,
    login,
    fetchMe,
    logout,
    logoutAll,
    reset,
  }
})
