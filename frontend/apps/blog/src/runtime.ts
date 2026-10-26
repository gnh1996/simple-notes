import {
  computed,
  createApp,
  nextTick,
  ref,
  watch,
} from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import type { RouteLocationNormalized } from 'vue-router'

import runtimeCss from './runtime.css?raw'

import { api } from './api'
import { blogComponents } from './components'
import { formatDate } from './format'
import { openLightbox } from './lightbox'
import { warmMarkdown } from './markdown'
import {
  RUNTIME_CONTRACT,
  applyThemeCss,
  injectThemeHtml,
  removeCloak,
  starterTheme,
  validateTheme,
} from './theme'
import {
  applyColorMode,
  archiveGroups,
  archives,
  archivesLoaded,
  colorMode,
  contentVersion,
  currentPath,
  emptyPager,
  keyword,
  message,
  nextPost,
  page,
  pager,
  post,
  posts,
  prevPost,
  searched,
  setColorMode,
  state,
  tag,
  tags,
  tagsLoaded,
  toPager,
  toggleColorMode,
} from './store'
import type { BlogState } from './store'
import type {
  Archive,
  Boot,
  PageResult,
  Pager,
  PostDetail,
  PostSummary,
  Site,
  SitePage,
  Tag,
} from './types'

declare global {
  interface Window {
    __BLOG_BOOT__?: Boot
    BlogRuntime?: {
      contract: number
      validateTheme: typeof validateTheme
    }
  }
}

// ---------------------------------------------------------------------------
// 基础资源
// ---------------------------------------------------------------------------

function applyRuntimeCss(): void {
  if (document.getElementById('blog-runtime')) {
    return
  }
  const style = document.createElement('style')
  style.id = 'blog-runtime'
  style.textContent = runtimeCss
  // 主题必须能覆盖组件基础样式：runtime.css 永远排在主题 CSS 之前
  const themeStyle = document.getElementById('blog-theme')
  if (themeStyle?.parentNode) {
    themeStyle.parentNode.insertBefore(style, themeStyle)
  } else {
    document.head.appendChild(style)
  }
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function showBootError(error: unknown): void {
  const text = error instanceof Error ? error.message : '站点加载失败'
  const boot = document.getElementById('blog-boot')
  if (boot) {
    // 主题可以用 CSS 隐藏加载层，但启动错误必须可见：内联 !important 兜底
    boot.setAttribute('data-error', '1')
    boot.setAttribute('role', 'alert')
    boot.style.setProperty('display', 'grid', 'important')
    boot.style.setProperty('opacity', '1', 'important')
    boot.style.setProperty('visibility', 'visible', 'important')
    boot.textContent = text
    return
  }
  document.body.insertAdjacentHTML(
    'beforeend',
    `<div class="bc-state" data-state="error" style="padding:48px 20px;text-align:center">${escapeHtml(text)}</div>`,
  )
}

// ---------------------------------------------------------------------------
// 启动：唯一路径 = 后端壳注入（docs/blog-runtime-theme.md §5.1）
// ---------------------------------------------------------------------------

function resolveBoot(): Boot {
  const injected = window.__BLOG_BOOT__
  if (!injected || !document.getElementById('app')) {
    throw new Error('请通过后端入口访问博客：缺少启动数据或主题')
  }
  return injected
}

function swapToStarter(reason: string): void {
  console.warn(`[blog] ${reason}，回落内置默认主题`)
  document.getElementById('app')?.remove()
  applyThemeCss(starterTheme.css)
  injectThemeHtml(starterTheme.html)
}

function prepareTheme(boot: Boot): void {
  const declared = boot.theme?.contract ?? RUNTIME_CONTRACT
  if (declared > RUNTIME_CONTRACT || RUNTIME_CONTRACT - declared > 1) {
    swapToStarter(`主题契约 ${declared} 与运行时 ${RUNTIME_CONTRACT} 不兼容`)
    return
  }
  // 壳里是已保存的版本；仍按 §8 做一次加载时校验（库内旧数据 / 预览草稿都走这里）
  const app = document.getElementById('app')
  const css = document.getElementById('blog-theme')?.textContent ?? ''
  if (!app || !validateTheme(app.outerHTML, css).ok) {
    swapToStarter('主题校验失败')
  }
}

// ---------------------------------------------------------------------------
// 路由与取数
// ---------------------------------------------------------------------------

const Blank = { render: () => null }
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: Blank },
    { path: '/posts/:slug', name: 'post', component: Blank },
    { path: '/tags', name: 'tags', component: Blank },
    { path: '/tags/:id', name: 'tag-posts', component: Blank },
    { path: '/archives', name: 'archives', component: Blank },
    { path: '/search', name: 'search', component: Blank },
    { path: '/about', name: 'page', component: Blank },
    { path: '/p/:slug', name: 'page', component: Blank },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: Blank },
  ],
  scrollBehavior(to, _from, savedPosition) {
    if (savedPosition) {
      return savedPosition
    }
    if (to.hash) {
      return { el: to.hash, top: 88 }
    }
    return { top: 0 }
  },
})

const route = router.currentRoute

/** 首页分页大小由站点配置决定；在启动时写入，供路由守卫取数使用。 */
const pageSize = ref(10)

interface RouteData {
  state: BlogState
  message: string
  posts: PostSummary[]
  pager: Pager
  post: PostDetail | null
  prevPost: PostSummary | null
  nextPost: PostSummary | null
  tags: Tag[] | null
  tag: Tag | null
  archives: Archive[] | null
  keyword: string
  page: SitePage | null
  searched: boolean
}

function emptyRouteData(): RouteData {
  return {
    state: 'loading',
    message: '',
    posts: [],
    pager: emptyPager(),
    post: null,
    prevPost: null,
    nextPost: null,
    tags: null,
    tag: null,
    archives: null,
    keyword: '',
    page: null,
    searched: false,
  }
}

function applyPage(data: RouteData, result: PageResult<PostSummary>): void {
  data.posts = result.items || []
  data.pager = toPager(result)
  data.state = data.posts.length ? 'ready' : 'empty'
}

function errorRouteData(error: unknown): RouteData {
  const data = emptyRouteData()
  data.state = 'error'
  data.message = error instanceof Error ? error.message : '加载失败'
  return data
}

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError'
}

/**
 * 路由取数（docs/blog-runtime-theme.md §5.1）：只组装快照，不写全局状态。
 * 快照在导航确认（afterEach）后一次性应用，取数期间旧页面保持可见。
 */
async function loadRoute(to: RouteLocationNormalized, signal: AbortSignal): Promise<RouteData> {
  const data = emptyRouteData()
  const query = to.query

  switch (to.name) {
    case 'home': {
      const result = await api<PageResult<PostSummary>>(
        '/api/blog/posts',
        {
          page: Number(query.page) || 1,
          size: pageSize.value || 10,
        },
        { signal },
      )
      applyPage(data, result)
      break
    }
    case 'post': {
      const slug = String(to.params.slug)
      const [detail] = await Promise.all([
        api<PostDetail>(`/api/blog/posts/${encodeURIComponent(slug)}`, undefined, { signal }),
        // 正文渲染依赖 Lute；与正文请求并行，避免标题先出、正文后到
        warmMarkdown().catch(() => undefined),
      ])
      data.post = detail
      data.state = 'ready'
      break
    }
    case 'tags': {
      const rows = await api<Tag[]>('/api/blog/tags', undefined, { signal })
      data.tags = rows
      data.state = rows.length ? 'ready' : 'empty'
      break
    }
    case 'tag-posts': {
      const id = String(to.params.id)
      const [allTags, result] = await Promise.all([
        api<Tag[]>('/api/blog/tags', undefined, { signal }),
        api<PageResult<PostSummary>>(
          '/api/blog/posts',
          {
            page: Number(query.page) || 1,
            tagId: id,
          },
          { signal },
        ),
      ])
      data.tags = allTags
      data.tag = allTags.find((item) => item.id === id) ?? null
      applyPage(data, result)
      break
    }
    case 'archives': {
      const [archiveRows, all] = await Promise.all([
        api<Archive[]>('/api/blog/archives', undefined, { signal }),
        api<PageResult<PostSummary>>('/api/blog/posts', { page: 1, size: 100 }, { signal }),
      ])
      data.archives = archiveRows || []
      applyPage(data, all)
      break
    }
    case 'search': {
      data.keyword = String(query.q || '')
      if (!data.keyword) {
        data.state = 'empty'
        break
      }
      data.searched = true
      const result = await api<PageResult<PostSummary>>(
        '/api/blog/search',
        {
          q: data.keyword,
          page: Number(query.page) || 1,
          size: 20,
        },
        { signal },
      )
      applyPage(data, result)
      break
    }
    case 'page': {
      const slug = to.path === '/about' ? 'about' : String(to.params.slug)
      const [sitePage] = await Promise.all([
        api<SitePage>(`/api/blog/pages/${encodeURIComponent(slug)}`, undefined, { signal }),
        warmMarkdown().catch(() => undefined),
      ])
      data.page = sitePage
      data.state = 'ready'
      break
    }
    default:
      data.state = 'ready'
  }
  return data
}

function applyRouteData(data: RouteData): void {
  state.value = data.state
  message.value = data.message
  posts.value = data.posts
  pager.value = data.pager
  post.value = data.post
  prevPost.value = data.prevPost
  nextPost.value = data.nextPost
  tag.value = data.tag
  keyword.value = data.keyword
  page.value = data.page
  searched.value = data.searched
  // tags / archives 是可选数据：本次路由没取就保留缓存，避免组件误判“为空”
  if (data.tags) {
    tags.value = data.tags
    tagsLoaded.value = true
  }
  if (data.archives) {
    archives.value = data.archives
    archivesLoaded.value = true
  }
  contentVersion.value += 1
}

let neighborSequence = 0

/** 上下篇不阻塞正文：导航确认后再补取，离开文章页则丢弃结果。 */
async function loadNeighbors(slug: string): Promise<void> {
  const sequence = ++neighborSequence
  try {
    const all = await api<PageResult<PostSummary>>('/api/blog/posts', { page: 1, size: 100 })
    if (sequence !== neighborSequence || post.value?.slug !== slug) {
      return
    }
    const items = all.items || []
    const index = items.findIndex((item) => item.slug === slug)
    if (index >= 0) {
      nextPost.value = index > 0 ? items[index - 1] : null
      prevPost.value = index < items.length - 1 ? items[index + 1] : null
    }
  } catch {
    /* 上下篇失败不影响正文 */
  }
}

let booted = false
let pendingData: RouteData | null = null
let activeController: AbortController | null = null
let navigationSequence = 0

function setNavigating(active: boolean): void {
  if (active) {
    document.documentElement.dataset.blogNavigating = '1'
  } else {
    delete document.documentElement.dataset.blogNavigating
  }
}

router.beforeResolve(async (to) => {
  activeController?.abort()
  const controller = new AbortController()
  activeController = controller
  const sequence = ++navigationSequence
  if (booted) {
    setNavigating(true)
  }

  try {
    const data = await loadRoute(to, controller.signal)
    if (sequence !== navigationSequence || controller.signal.aborted) {
      return false
    }
    pendingData = data
    return true
  } catch (error) {
    if (sequence !== navigationSequence || controller.signal.aborted || isAbortError(error)) {
      return false
    }
    pendingData = errorRouteData(error)
    return true
  }
})

router.afterEach((to) => {
  currentPath.value = to.fullPath
  const data = pendingData
  pendingData = null
  if (data) {
    applyRouteData(data)
  }
  activeController = null
  setNavigating(false)
  booted = true
  if (to.name === 'post' && post.value) {
    void loadNeighbors(post.value.slug)
  }
})

router.onError(() => {
  setNavigating(false)
})

// ---------------------------------------------------------------------------
// 动作
// ---------------------------------------------------------------------------

function go(targetPage: number): void {
  if (targetPage < 1 || targetPage > pager.value.totalPages) {
    return
  }
  router.push({
    path: route.value.path,
    query: { ...route.value.query, page: String(targetPage) },
  })
}

function search(value: string): void {
  const q = String(value || '').trim()
  router.push({ path: '/search', query: q ? { q } : {} })
}

function isNavActive(to: string): boolean {
  if (to === '/') {
    return route.value.path === '/'
  }
  return route.value.path === to || route.value.path.startsWith(`${to}/`)
}

function scrollToTop(): void {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

// ---------------------------------------------------------------------------
// 应用装配
// ---------------------------------------------------------------------------

let mountTemplate = ''
let markdownRendered = false

// 首帧是否等到正文真正渲染：博客正文由 Lute 异步输出，早于它揭开 cloak 会只剩标题
window.addEventListener('blog:markdown-rendered', () => {
  markdownRendered = true
})

function waitForInitialMarkdown(): Promise<void> {
  if ((!post.value?.content && !page.value?.content) || markdownRendered) {
    return Promise.resolve()
  }
  return new Promise((resolve) => {
    const finish = (): void => {
      window.removeEventListener('blog:markdown-rendered', finish)
      resolve()
    }
    window.addEventListener('blog:markdown-rendered', finish, { once: true })
    window.setTimeout(finish, 800)
  })
}

async function main(): Promise<void> {
  document.documentElement.dataset.blogRuntime = '1'
  applyRuntimeCss()
  let boot: Boot
  try {
    boot = resolveBoot()
  } catch (error) {
    showBootError(error)
    throw error
  }

  prepareTheme(boot)
  const site = ref<Site>(boot.site)
  pageSize.value = boot.site.pageSize || 10
  applyColorMode(
    localStorage.getItem('sn.blog.colorMode') ?? boot.themeMode ?? boot.site.themeMode ?? 'system',
  )

  const root = document.getElementById('app')
  if (!root) {
    showBootError(new Error('主题缺少 id="app" 根节点'))
    return
  }
  mountTemplate = root.innerHTML

  const app = createApp({
    template: mountTemplate,
    setup() {
      return {
        site,
        nav: computed(() => site.value.nav || []),
        route,
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
        go,
        search,
        isNavActive,
        setColorMode,
        toggleColorMode,
        scrollToTop,
        openLightbox,
        formatDate,
      }
    },
  })

  Object.entries(blogComponents).forEach(([name, component]) => {
    app.component(name, component)
  })
  app.use(router)

  await router.isReady()
  app.mount(root)
  await nextTick()
  await waitForInitialMarkdown()
  removeCloak()
  await nextTick()
  document.documentElement.dataset.blogReady = '1'

  // #app 自身的属性不在编译模板内：按契约补齐 DOM 钩子（§3.6）
  for (const attribute of [...root.attributes]) {
    if (attribute.name.startsWith(':')) {
      root.removeAttribute(attribute.name)
    }
  }
  watch(
    [route, state],
    () => {
      const name = typeof route.value.name === 'string' ? route.value.name : 'not-found'
      root.dataset.blogPage = name
      root.dataset.state = state.value
      root.dataset.blogShell = ''
    },
    { immediate: true },
  )

  const media = window.matchMedia('(prefers-color-scheme: dark)')
  media.addEventListener('change', () => {
    if (!localStorage.getItem('sn.blog.colorMode')) {
      applyColorMode('system')
    }
  })

  const adminOrigin = boot.adminOrigin || '*'
  window.BlogRuntime = { contract: RUNTIME_CONTRACT, validateTheme }

  function reply(payload: Record<string, unknown>): void {
    if (window.parent && window.parent !== window) {
      window.parent.postMessage(payload, adminOrigin)
    }
  }

  window.addEventListener('message', (event) => {
    if (adminOrigin !== '*' && event.origin !== adminOrigin) {
      return
    }
    const data = event.data as Record<string, unknown> | null
    if (!data || typeof data !== 'object') {
      return
    }
    if (data.type === 'blog-preview:color-mode' && typeof data.mode === 'string') {
      setColorMode(data.mode as 'light' | 'dark' | 'system')
    } else if (data.type === 'blog-preview:scroll-to' && typeof data.selector === 'string') {
      const target = document.querySelector(data.selector)
      if (target) {
        target.scrollIntoView({ behavior: 'smooth', block: 'start' })
      }
    } else if (data.type === 'blog-preview:validate') {
      const html = typeof data.html === 'string' ? data.html : mountTemplate
      const css = typeof data.css === 'string' ? data.css : ''
      const result = validateTheme(html, css)
      reply({
        type: 'blog-preview:validated',
        id: data.id,
        ok: result.ok,
        errors: result.errors,
      })
    }
  })

  reply({ type: 'blog-preview:ready' })
}

// 站内链接交给 history 路由，保留原生 <a> 语义
document.addEventListener('click', (event) => {
  const target = event.target
  if (!(target instanceof Element)) {
    return
  }
  const anchor = target.closest<HTMLAnchorElement>('a[href]')
  if (!anchor) {
    return
  }
  if (anchor.target || anchor.hasAttribute('download')) {
    return
  }
  if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
    return
  }
  const url = new URL(anchor.href, location.href)
  if (url.origin !== location.origin) {
    return
  }
  const internalAsset =
    url.pathname.startsWith('/api/') ||
    url.pathname.startsWith('/assets/') ||
    url.pathname.startsWith('/src/') ||
    url.pathname.startsWith('/@vite/') ||
    url.pathname.startsWith('/@fs/') ||
    url.pathname.startsWith('/node_modules/')
  if (internalAsset) {
    return
  }
  event.preventDefault()
  router.push(url.pathname + url.search + url.hash)
})

void main().catch((error) => {
  console.error('[blog] 启动失败', error)
  if (document.documentElement.dataset.blogReady !== '1') {
    showBootError(error)
  }
})
