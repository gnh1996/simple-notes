import { Avatar, Menu as MenuIcon, User } from '@element-plus/icons-vue'
import type { Component } from 'vue'

/**
 * 页面注册表（docs/auth-rbac.md §6.2）：权限码 → 路径 + 组件 + 图标，随构建产物发布。
 *
 * - 服务端菜单树（`me.menus`）只携带 名称 / 排序 / 层级，不含路径；
 * - 注册表命中 = 页面型菜单（注册路由）；未命中 = 目录（仅递归子节点）；
 * - `hidden` 页面（详情 / 编辑类）后续在此登记并可标 `hidden: true`。
 */
export interface PageRegistryItem {
  /** 应用内绝对路径（路由路径的唯一来源） */
  path: string
  /** 路由名（全局唯一） */
  name: string
  /** 页面标题兜底（侧边栏 / 页签 / 面包屑优先用服务端菜单名，改名不发版） */
  title: string
  /** 首页快捷入口描述；不填则不进入快捷入口 */
  description?: string
  icon?: Component
  component: () => Promise<unknown>
}

export const PAGE_REGISTRY: Record<string, PageRegistryItem> = {
  'admin:user': {
    path: '/system/user',
    name: 'UserList',
    title: '用户管理',
    description: '账号、状态与角色分配',
    icon: User,
    component: () => import('@/views/system/user/UserListView.vue'),
  },
  'admin:role': {
    path: '/system/role',
    name: 'RoleList',
    title: '角色管理',
    description: '角色与权限节点授权',
    icon: Avatar,
    component: () => import('@/views/system/role/RoleListView.vue'),
  },
  'admin:menu': {
    path: '/system/menu',
    name: 'MenuList',
    title: '菜单管理',
    description: '权限目录的改名 / 调序 / 启停',
    icon: MenuIcon,
    component: () => import('@/views/system/menu/MenuListView.vue'),
  },
}
