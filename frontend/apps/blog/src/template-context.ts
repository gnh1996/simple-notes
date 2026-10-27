import { computed, reactive, watch } from 'vue'
import type { Ref } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'

import { formatDate } from './format'
import { openLightbox } from './lightbox'
import {
  archiveGroups,
  archives,
  colorMode,
  currentPath,
  keyword,
  message,
  page,
  pager,
  post,
  posts,
  prevPost,
  nextPost,
  searched,
  setColorMode,
  state,
  tag,
  tags,
  toggleColorMode,
} from './store'
import type { Site } from './types'

/** 由 runtime 提供、与路由实例绑定的部分（模板上下文自己拿不到）。 */
export interface TemplateContextDeps {
  site: Ref<Site>
  route: Ref<RouteLocationNormalizedLoaded>
  go: (page: number) => void
  search: (keyword: string) => void
  isNavActive: (to: string) => boolean
  scrollToTop: () => void
}

/**
 * 模板上下文（docs/blog-runtime-theme.md §3）：主题可见的全部数据 / 动作 / 辅助函数。
 *
 * 返回的键必须与 `contract.ts` 登记的内容**完全一致**——登记了没暴露、暴露了没登记
 * 都会让 `pnpm check:contract` 失败。新增能力请同时改两处（先登记，再暴露）。
 */
export function createTemplateContext(deps: TemplateContextDeps) {
  // 主题自有的 UI 状态（契约 §3.2 中唯一可写的数据）：抽屉、标签页、筛选等。
  // 路由切换即清空，避免「上一页打开的抽屉」跟着跑到下一页；不写 localStorage。
  const ui = reactive<Record<string, unknown>>({})
  watch(currentPath, () => {
    for (const key of Object.keys(ui)) {
      delete ui[key]
    }
  })

  return {
    // 数据
    site: deps.site,
    nav: computed(() => deps.site.value.nav || []),
    route: deps.route,
    state,
    message,
    posts,
    pager,
    post,
    prevPost,
    nextPost,
    tags,
    tag,
    archives,
    archiveGroups,
    keyword,
    page,
    searched,
    colorMode,
    ui,
    // 辅助函数
    isNavActive: deps.isNavActive,
    formatDate,
    // 动作
    go: deps.go,
    search: deps.search,
    setColorMode,
    toggleColorMode,
    scrollToTop: deps.scrollToTop,
    openLightbox,
  }
}
