import { computed, ref } from 'vue'

import type {
  Archive,
  ArchiveGroup,
  ListSort,
  Pager,
  PostDetail,
  PostSummary,
  SitePage,
  SortField,
  Tag,
} from './types'

/** 运行时状态：主题只能读取，不能写（契约 §3.2）。 */

export type BlogState = 'loading' | 'empty' | 'error' | 'ready'

export const state = ref<BlogState>('loading')
export const message = ref('')
export const posts = ref<PostSummary[]>([])
export const pager = ref<Pager>(emptyPager())
export const post = ref<PostDetail | null>(null)
export const prevPost = ref<PostSummary | null>(null)
export const nextPost = ref<PostSummary | null>(null)
/** 当前列表 / 详情上下文解析后的排序参数（只读）。 */
export const listSort = ref<ListSort>({ pinnedFirst: true, direction: 'desc', sortBy: 'displayAt' })
export const tags = ref<Tag[]>([])
/** 标签 / 归档是否已由路由快照或组件请求填充：供组件区分“未加载”与“确实为空”。 */
export const tagsLoaded = ref(false)
export const tag = ref<Tag | null>(null)
export const archives = ref<Archive[]>([])
export const archivesLoaded = ref(false)
export const keyword = ref('')
export const page = ref<SitePage | null>(null)
export const searched = ref(false)
export const contentVersion = ref(0)
export const currentPath = ref('/')
export const colorMode = ref<'light' | 'dark'>('light')

export function emptyPager(): Pager {
  return { page: 1, size: 10, total: 0, totalPages: 1, hasPrev: false, hasNext: false }
}

export function toPager(result: { page?: number; size?: number; total?: number }): Pager {
  const size = result.size || 10
  const total = result.total || 0
  const current = result.page || 1
  const totalPages = Math.max(1, Math.ceil(total / size))
  return {
    page: current,
    size,
    total,
    totalPages,
    hasPrev: current > 1,
    hasNext: current < totalPages,
  }
}

export function postDate(post: PostSummary, sortBy: SortField = listSort.value.sortBy): string {
  return post[sortBy] || post.displayAt
}

export const archiveGroups = computed<ArchiveGroup[]>(() => {
  const sortBy = listSort.value.sortBy
  const groups = new Map<string, ArchiveGroup>()
  for (const item of posts.value) {
    const value = postDate(item, sortBy)
    const date = value ? new Date(value) : null
    if (!date || Number.isNaN(date.getTime())) {
      continue
    }
    // 与后端 /api/blog/archives 一致：年月按 UTC 计算。
    const year = date.getUTCFullYear()
    const month = date.getUTCMonth() + 1
    const key = `${year}-${month}`
    if (!groups.has(key)) {
      groups.set(key, { key, year, month, posts: [] })
    }
    groups.get(key)?.posts.push(item)
  }
  return [...groups.values()]
})

export function applyColorMode(mode: string): void {
  const resolved =
    mode === 'system'
      ? window.matchMedia('(prefers-color-scheme: dark)').matches
        ? 'dark'
        : 'light'
      : mode === 'dark'
        ? 'dark'
        : 'light'
  colorMode.value = resolved
  document.documentElement.dataset.colorMode = resolved
}

export function setColorMode(mode: 'light' | 'dark' | 'system'): void {
  if (mode === 'system') {
    localStorage.removeItem('sn.blog.colorMode')
  } else {
    localStorage.setItem('sn.blog.colorMode', mode)
  }
  applyColorMode(mode)
}

export function toggleColorMode(): void {
  setColorMode(colorMode.value === 'dark' ? 'light' : 'dark')
}
