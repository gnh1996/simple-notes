import type { MenuNode } from '@simple-notes/api'
import { createRouter, createWebHistory, RouterView, type RouteRecordRaw } from 'vue-router'
import { h } from 'vue'

import { PAGE_REGISTRY } from './page-registry'

declare module 'vue-router' {
  interface RouteMeta {
    /** 匿名可访问（登录页 / 404） */
    public?: boolean
    /** 页面标题（写 document.title + 页签） */
    title?: string
    /** 权限码（页面注册表映射；目录节点不注册） */
    permCode?: string
    /** 不在侧边栏展示 */
    hidden?: boolean
    /** 固定页签（首页） */
    affix?: boolean
    /** 动态注册（按服务端菜单树生成，权限变化时整组移除重建） */
    dynamic?: boolean
  }
}

/** 目录节点（无对应页面）的透传容器：仅渲染子路由 */
const PassthroughView = { name: 'PassthroughView', render: () => h(RouterView) }

const baseRoutes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/LoginView.vue'),
    meta: { public: true, title: '登录', hidden: true },
  },
  {
    path: '/',
    name: 'Root',
    component: () => import('@/layouts/AdminLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/DashboardView.vue'),
        meta: { title: '首页', affix: true },
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/profile/ProfileView.vue'),
        meta: { title: '个人中心', hidden: true },
      },
      {
        path: 'notes/:id',
        name: 'NoteEditor',
        component: () => import('@/views/notes/NoteEditorView.vue'),
        meta: { title: '编辑笔记', hidden: true },
      },
      {
        path: 'posts/:id',
        name: 'PostEditor',
        component: () => import('@/views/posts/PostEditorView.vue'),
        meta: { title: '编辑文章', hidden: true },
      },
      {
        path: 'trash',
        name: 'Trash',
        component: () => import('@/views/trash/TrashView.vue'),
        meta: { title: '回收站', hidden: true },
      },
      {
        path: ':pathMatch(.*)*',
        name: 'NotFound',
        component: () => import('@/views/fallback/NotFoundView.vue'),
        meta: { title: '页面不存在', hidden: true },
      },
    ],
  },
]

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: baseRoutes,
})

/**
 * 服务端菜单树 → 动态路由（docs/auth-rbac.md §6.2）：
 *
 * - 注册表命中 → 注册页面路由（路径 / 组件来自注册表；名称 / 排序 / 层级以服务端为准）；
 * - 未命中 → 目录：仅递归子节点，自身注册透传容器承载层级（空目录照常显示，仅不注册路由）。
 */
function menuToRoutes(node: MenuNode): RouteRecordRaw[] {
  const page = PAGE_REGISTRY[node.code]
  if (page) {
    return [
      {
        path: page.path,
        name: page.name,
        component: page.component,
        meta: { title: node.name || page.title, permCode: node.code, dynamic: true },
      },
    ]
  }

  const children = (node.children ?? []).flatMap(menuToRoutes)
  if (children.length === 0) {
    return []
  }
  return [
    {
      path: `/${node.code.replaceAll(':', '/')}`,
      name: `dir-${node.code}`,
      component: PassthroughView,
      meta: { title: node.name, permCode: node.code, dynamic: true },
      children,
    },
  ]
}

/** 先移除全部动态路由再按最新菜单树重建（权限变化 / 403 刷新时调用） */
export function rebuildMenuRoutes(menus: MenuNode[]): void {
  for (const route of router.getRoutes()) {
    if (route.meta.dynamic && route.name) {
      router.removeRoute(route.name)
    }
  }
  for (const node of menus) {
    for (const route of menuToRoutes(node)) {
      router.addRoute('Root', route)
    }
  }
}

/**
 * 路由守卫（只做会话层判断，无路由级权限判断——"页面是否注册"即权限结论）：
 *
 * - 未登录 → 登录页（带 redirect）；
 * - 有凭证但身份未拉取（F5 直连深链）→ 先调 `/api/auth/me` 注册路由，再重匹配放行；
 * - 登录页：有凭证则确认身份后回首页，避免已登录用户重复登录。
 */
router.beforeEach(async (to) => {
  const { useAuthStore } = await import('@/stores/auth')
  const auth = useAuthStore()

  if (to.meta.public) {
    if (to.name === 'Login' && auth.token) {
      // 有凭证：先确认身份，成功才回首页；失败则留在登录页。
      // meError 保护：上一次身份拉取已失败就不再重试，否则 /login ⇄ / 会无限重定向。
      if (auth.ready) return { path: '/' }
      if (!auth.meError) {
        try {
          await auth.fetchMe()
          rebuildMenuRoutes(auth.menus)
          return { path: '/' }
        } catch {
          return true
        }
      }
    }
    return true
  }
  if (!auth.token) {
    return { name: 'Login', query: to.fullPath === '/' ? undefined : { redirect: to.fullPath } }
  }
  if (!auth.ready) {
    // 上一次身份拉取（`/api/auth/me`）失败过：本次导航不再重试，直接回登录页（重新登录 / 刷新后再试）
    if (auth.meError) {
      return { name: 'Login', query: { redirect: to.fullPath } }
    }
    try {
      await auth.fetchMe()
    } catch {
      // 凭证无效（401 由 api 层清理并跳转）或服务端不可达：兜底回登录页
      return { name: 'Login', query: { redirect: to.fullPath } }
    }
    rebuildMenuRoutes(auth.menus)
    // 用 fullPath 重新解析（不能用 { ...to }：首载时 to 已被 404 兜底路由匹配、带 name，
    // 展开后会按 name 解析回兜底路由）
    return { path: to.fullPath, replace: true }
  }
  return true
})

router.afterEach((to) => {
  const title = to.meta.title
  document.title = title ? `${title} · Simple Notes` : 'Simple Notes · 管理端'
})

export default router
