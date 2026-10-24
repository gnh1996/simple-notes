import {
  Avatar,
  Brush,
  Document,
  Menu as MenuIcon,
  Notebook,
  Picture,
  PriceTag,
  Setting,
  User,
} from '@element-plus/icons-vue'
import type { Component } from 'vue'

/**
 * 页面注册表（docs/auth-rbac.md §6.2）：权限码 → 路径 + 组件 + 图标，随构建产物发布。
 *
 * - 服务端菜单树（`me.menus`）只携带 名称 / 排序 / 层级，不含路径；
 * - 注册表命中 = 页面型菜单（注册路由）；未命中 = 目录（仅递归子节点）；
 * - 详情 / 编辑页在 baseRoutes 中登记为 hidden 路由，由后端权限终审。
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
  'admin:note': {
    path: '/notes',
    name: 'NoteList',
    title: '随手记',
    description: '默认记事本，支持标签、文件夹与转为博客',
    icon: Notebook,
    component: () => import('@/views/notes/NoteListView.vue'),
  },
  'admin:post': {
    path: '/posts',
    name: 'PostList',
    title: '博客管理',
    description: '草稿、发布快照与下架',
    icon: Document,
    component: () => import('@/views/posts/PostListView.vue'),
  },
  'admin:tag': {
    path: '/tags',
    name: 'TagList',
    title: '标签管理',
    description: '全局标签、使用量与内容分配',
    icon: PriceTag,
    component: () => import('@/views/tags/TagListView.vue'),
  },
  'admin:media': {
    path: '/media',
    name: 'MediaList',
    title: '媒体库',
    description: '图片上传与站内稳定 URL',
    icon: Picture,
    component: () => import('@/views/media/MediaListView.vue'),
  },
  'admin:site': {
    path: '/site',
    name: 'SiteSettings',
    title: '站点设置',
    description: '品牌、导航、作者与独立页面',
    icon: Setting,
    component: () => import('@/views/site/SiteSettingsView.vue'),
  },
  'admin:theme': {
    path: '/themes',
    name: 'ThemeList',
    title: '主题管理',
    description: '主题版本、预览与应用',
    icon: Brush,
    component: () => import('@/views/themes/ThemeListView.vue'),
  },
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
