import {
  ApiError,
  createApiClient,
  createSimpleNotesApi,
  type SimpleNotesApi,
} from '@simple-notes/api'
import { ElMessage } from 'element-plus'

/** 凭证 localStorage key（docs/auth-rbac.md §6.2：admin 端独立 key，与 blog 互不干扰） */
const TOKEN_KEY = 'sn.auth.admin'

export function getStoredToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function storeToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearStoredToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

/**
 * 401：会话失效 → 清凭证回登录页。
 *
 * 只在"已有凭证"时触发；并发 401 用标记去重。当前已在登录页时不跳转、不提示
 * "会话失效"——此时的 401 来自登录接口本身，凭据错误由登录页展示。
 * 动态 import 规避与 store / router 的循环依赖。
 */
let redirecting = false
export async function handleUnauthorized(): Promise<void> {
  if (redirecting) return
  redirecting = true
  try {
    const [{ useAuthStore }, { default: router }] = await Promise.all([
      import('@/stores/auth'),
      import('@/router'),
    ])
    const auth = useAuthStore()
    if (!auth.token) return
    const onLogin = router.currentRoute.value.name === 'Login'
    auth.reset()
    if (!onLogin) {
      await router.replace({
        name: 'Login',
        query: { redirect: router.currentRoute.value.fullPath },
      })
      ElMessage.warning('登录状态已失效，请重新登录')
    }
  } finally {
    redirecting = false
  }
}

/**
 * 403 PERMISSION_DENIED：权限刚被调整 → 刷新 me 并重建菜单 / 路由（docs/auth-rbac.md §6.2，
 * 避免陈旧界面）。做节流：一次操作可能并发多个请求同时 403。
 */
let lastSyncAt = 0
async function handlePermissionDenied(): Promise<void> {
  const now = Date.now()
  if (now - lastSyncAt < 3000) return
  lastSyncAt = now
  const [{ useAuthStore }, { rebuildMenuRoutes }] = await Promise.all([
    import('@/stores/auth'),
    import('@/router'),
  ])
  const auth = useAuthStore()
  try {
    await auth.fetchMe()
    rebuildMenuRoutes(auth.menus)
    ElMessage.warning('权限已更新，菜单已同步')
  } catch {
    // me 也失败时交给 401 处理链路
  }
}

export const api: SimpleNotesApi = createSimpleNotesApi(
  createApiClient({
    prepareRequest(init) {
      const token = getStoredToken()
      if (!token) return init
      const headers = new Headers(init.headers)
      headers.set('Authorization', `Bearer ${token}`)
      return { ...init, headers }
    },
    onError(error) {
      if (error.status === 401) {
        void handleUnauthorized()
      } else if (error.status === 403 && error.code === 'PERMISSION_DENIED') {
        void handlePermissionDenied()
      }
    },
  }),
)

export const {
  meta: metaApi,
  auth: authApi,
  users: userApi,
  roles: roleApi,
  menus: menuApi,
  noteFolders: noteFolderApi,
  notes: noteApi,
  posts: postApi,
  tags: tagApi,
  trash: trashApi,
  media: mediaApi,
  blog: blogApi,
  site: siteApi,
  themes: themeApi,
} = api
export { ApiError }
