/** 博客 runtime 的数据形状（docs/blog-runtime-theme.md §3.2，契约冻结）。 */

export interface Author {
  name: string
  avatarUrl: string | null
  bio: string
}

export interface SocialLink {
  type: string | null
  label: string | null
  url: string | null
}

export interface NavItem {
  key: string
  label: string
  to: string
}

export interface Site {
  name: string
  tagline: string
  description: string
  logoUrl: string | null
  faviconUrl: string | null
  baseUrl: string | null
  icp: string | null
  policeRecord: string | null
  author: Author
  social: SocialLink[]
  nav: NavItem[]
  themeMode: string
  themeOverrides: Record<string, string> | null
  footerText: string
  pageSize: number
}

export interface TagBrief {
  id: string
  name: string
}

export type SortField = 'displayAt' | 'firstPublishedAt' | 'lastPublishedAt'

export interface ListSort {
  pinnedFirst: boolean
  direction: 'asc' | 'desc'
  sortBy: SortField
}

/** 主题可见的路由视图（只暴露模板需要的字段，不泄漏 vue-router 内部类型）。 */
export interface RouteView {
  name: string | symbol | null | undefined
  path: string
  params: Record<string, string | string[]>
  query: Record<string, string | string[] | null>
  hash: string
}

/** 目录项（`blog-toc` 的 root slot 作用域）。 */
export interface TocItem {
  id: string
  text: string
  depth: number
}

/** 归档列表按当前排序字段分组后的结果（`archiveGroups`）。 */
export interface ArchiveGroup {
  key: string
  year: number
  month: number
  posts: PostSummary[]
}

export interface PostSummary {
  slug: string
  title: string
  summary: string
  coverUrl: string | null
  tags: TagBrief[]
  pinned: boolean
  firstPublishedAt: string
  lastPublishedAt: string
  authoredAt: string | null
  displayAt: string
  readingMinutes: number
}

export interface PostDetail extends PostSummary {
  content: string
  authorName: string
  prevPost: PostSummary | null
  nextPost: PostSummary | null
}

export interface Tag {
  id: string
  name: string
  count: number
}

export interface Archive {
  year: number
  month: number
  count: number
}

export interface SitePage {
  id: string
  slug: string
  title: string
  content: string
  visible: boolean
  sort: number
  updatedAt: string
}

export interface Pager {
  page: number
  size: number
  total: number
  totalPages: number
  hasPrev: boolean
  hasNext: boolean
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

/** 主题负载：内置（id = default）或数据库中的不可变版本 */
export interface ThemePayload {
  id: string
  versionNo: number
  contract: number
  html: string
  css: string
}

export interface BootThemeMeta {
  id: string
  versionNo: number
  contract: number
}

/** 后端壳注入的启动数据（唯一启动路径） */
export interface Boot {
  site: Site
  themeMode: string
  adminOrigin: string
  contract: number
  theme?: BootThemeMeta | null
  preview?: boolean
}

export interface ThemeValidationError {
  line: number
  message: string
}

export interface ThemeValidationResult {
  ok: boolean
  errors: ThemeValidationError[]
}
