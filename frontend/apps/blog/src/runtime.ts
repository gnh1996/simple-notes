import {
  computed,
  createApp,
  nextTick,
  ref,
  watch,
} from 'vue'
import { createRouter, createWebHistory } from 'vue-router'

import runtimeCss from './runtime.css?raw'

import { api } from './api'
import { blogComponents } from './components'
import { formatDate } from './format'
import { openLightbox } from './lightbox'
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
  toPager,
  toggleColorMode,
} from './store'
import type {
  Boot,
  PageResult,
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
  const loading = document.getElementById('blog-dev-loading')
  if (loading) {
    loading.textContent = text
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

/** 首页分页大小由站点配置决定；在启动时写入，供 load() 使用。 */
const pageSize = ref(10)

router.afterEach(() => {
  currentPath.value = route.value.fullPath
})

function applyPage(result: PageResult<PostSummary>): void {
  posts.value = result.items || []
  pager.value = toPager(result)
  state.value = posts.value.length ? 'ready' : 'empty'
  contentVersion.value += 1
}

async function loadPost(slug: string): Promise<void> {
  post.value = await api<PostDetail>(`/api/blog/posts/${encodeURIComponent(slug)}`)
  prevPost.value = null
  nextPost.value = null
  try {
    const all = await api<PageResult<PostSummary>>('/api/blog/posts', { page: 1, size: 100 })
    const items = all.items || []
    const index = items.findIndex((item) => item.slug === slug)
    if (index >= 0) {
      nextPost.value = index > 0 ? items[index - 1] : null
      prevPost.value = index < items.length - 1 ? items[index + 1] : null
    }
  } catch {
    /* 上下篇失败不影响正文 */
  }
  state.value = 'ready'
  contentVersion.value += 1
}

async function load(): Promise<void> {
  state.value = 'loading'
  message.value = ''
  searched.value = false
  post.value = null
  page.value = null
  const current = route.value
  const query = current.query

  try {
    switch (current.name) {
      case 'home': {
        const result = await api<PageResult<PostSummary>>('/api/blog/posts', {
          page: Number(query.page) || 1,
          size: pageSize.value || 10,
        })
        applyPage(result)
        break
      }
      case 'post':
        await loadPost(String(current.params.slug))
        break
      case 'tags':
        tags.value = await api<Tag[]>('/api/blog/tags')
        state.value = tags.value.length ? 'ready' : 'empty'
        break
      case 'tag-posts': {
        const id = String(current.params.id)
        const [allTags, result] = await Promise.all([
          api<Tag[]>('/api/blog/tags'),
          api<PageResult<PostSummary>>('/api/blog/posts', {
            page: Number(query.page) || 1,
            tagId: id,
          }),
        ])
        tags.value = allTags
        tag.value = allTags.find((item) => item.id === id) ?? null
        applyPage(result)
        break
      }
      case 'archives': {
        const [archiveRows, all] = await Promise.all([
          api<Array<{ year: number; month: number; count: number }>>('/api/blog/archives'),
          api<PageResult<PostSummary>>('/api/blog/posts', { page: 1, size: 100 }),
        ])
        archives.value = archiveRows || []
        applyPage(all)
        break
      }
      case 'search': {
        keyword.value = String(query.q || '')
        if (!keyword.value) {
          posts.value = []
          pager.value = emptyPager()
          state.value = 'empty'
          break
        }
        searched.value = true
        const result = await api<PageResult<PostSummary>>('/api/blog/search', {
          q: keyword.value,
          page: Number(query.page) || 1,
          size: 20,
        })
        applyPage(result)
        break
      }
      case 'page': {
        const slug = current.path === '/about' ? 'about' : String(current.params.slug)
        page.value = await api<SitePage>(`/api/blog/pages/${encodeURIComponent(slug)}`)
        state.value = 'ready'
        contentVersion.value += 1
        break
      }
      default:
        state.value = 'ready'
    }
  } catch (error) {
    state.value = 'error'
    message.value = error instanceof Error ? error.message : '加载失败'
  }
}

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

async function main(): Promise<void> {
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
      watch(() => route.value.fullPath, load, { immediate: true })
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
  removeCloak()
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
})
