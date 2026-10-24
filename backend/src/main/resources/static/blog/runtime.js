/**
 * blog runtime（docs/blog-runtime-theme.md §3）
 *
 * 职责：路由、取数、四态、Markdown 渲染、明暗、动作、组件词汇表。
 * 主题只提供 HTML + CSS，不包含任何脚本。
 */

import { computed, createApp, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'

import MarkdownItGitHubAlerts from '/blog/vendor/markdown-it-github-alerts.mjs'
import hljs from '/blog/vendor/hljs.mjs'

const BOOT = window.__BLOG_BOOT__ || {}
const themeMode = BOOT.themeMode || 'system'

// ---------------------------------------------------------------------------
// 状态
// ---------------------------------------------------------------------------

const state = ref('loading')
const message = ref('')
const posts = ref([])
const pager = ref(emptyPager())
const post = ref(null)
const prevPost = ref(null)
const nextPost = ref(null)
const tags = ref([])
const tag = ref(null)
const archives = ref([])
const keyword = ref('')
const page = ref(null)
const searched = ref(false)
const contentVersion = ref(0)
const colorMode = ref(document.documentElement.dataset.colorMode || 'light')

function emptyPager() {
  return { page: 1, size: 10, total: 0, totalPages: 1, hasPrev: false, hasNext: false }
}

function toPager(result) {
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

function applyPage(result) {
  posts.value = result.items || []
  pager.value = toPager(result)
  state.value = posts.value.length ? 'ready' : 'empty'
  contentVersion.value += 1
}

async function api(path, params) {
  const url = new URL(path, location.origin)
  if (params) {
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        url.searchParams.set(key, value)
      }
    })
  }
  const response = await fetch(url, { headers: { Accept: 'application/json' } })
  if (!response.ok) {
    let detail = `请求失败（${response.status}）`
    try {
      const body = await response.json()
      if (body && body.message) detail = body.message
    } catch {
      /* ignore */
    }
    const error = new Error(detail)
    error.status = response.status
    throw error
  }
  return response.json()
}

// ---------------------------------------------------------------------------
// Markdown（与 admin 方言配置等价：breaks:false / github-alerts / html:false）
// ---------------------------------------------------------------------------

const md = window.markdownit({
  html: false,
  linkify: true,
  breaks: false,
  highlight(code, language) {
    if (!language) return ''
    const name = hljs.getLanguage(language) ? language : 'plaintext'
    try {
      return hljs.highlight(code, { language: name, ignoreIllegals: true }).value
    } catch {
      return ''
    }
  },
})

md.use(MarkdownItGitHubAlerts)

const defaultHeadingOpen = md.renderer.rules.heading_open
  || ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options))

function slugify(text) {
  return String(text)
    .trim()
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, '-')
    .replace(/^-+|-+$/g, '') || 'section'
}

md.renderer.rules.heading_open = (tokens, idx, options, env, self) => {
  const inline = tokens[idx + 1]
  const text = inline && inline.content ? inline.content.replace(/<[^>]+>/g, '') : ''
  tokens[idx].attrSet('id', slugify(text))
  return defaultHeadingOpen(tokens, idx, options, env, self)
}

function renderMarkdown(source) {
  return source ? md.render(source) : ''
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
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.hash) return { el: to.hash, top: 88 }
    return { top: 0 }
  },
})

async function loadPost(slug) {
  post.value = await api(`/api/blog/posts/${encodeURIComponent(slug)}`)
  prevPost.value = null
  nextPost.value = null
  try {
    const all = await api('/api/blog/posts', { page: 1, size: 100 })
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

async function load() {
  state.value = 'loading'
  message.value = ''
  searched.value = false
  post.value = null
  page.value = null
  const query = router.currentRoute.value.query
  const current = router.currentRoute.value

  try {
    switch (current.name) {
      case 'home': {
        const result = await api('/api/blog/posts', {
          page: Number(query.page) || 1,
          size: (BOOT.site && BOOT.site.pageSize) || 10,
        })
        applyPage(result)
        break
      }
      case 'post':
        await loadPost(String(current.params.slug))
        break
      case 'tags':
        tags.value = await api('/api/blog/tags')
        state.value = tags.value.length ? 'ready' : 'empty'
        break
      case 'tag-posts': {
        const id = String(current.params.id)
        const [allTags, result] = await Promise.all([
          api('/api/blog/tags'),
          api('/api/blog/posts', { page: Number(query.page) || 1, tagId: id }),
        ])
        tags.value = allTags
        tag.value = allTags.find((item) => item.id === id) || null
        applyPage(result)
        break
      }
      case 'archives': {
        const [archiveRows, all] = await Promise.all([
          api('/api/blog/archives'),
          api('/api/blog/posts', { page: 1, size: 100 }),
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
        const result = await api('/api/blog/search', {
          q: keyword.value,
          page: Number(query.page) || 1,
          size: 20,
        })
        applyPage(result)
        break
      }
      case 'page': {
        const slug = current.path === '/about' ? 'about' : String(current.params.slug)
        page.value = await api(`/api/blog/pages/${encodeURIComponent(slug)}`)
        state.value = 'ready'
        contentVersion.value += 1
        break
      }
      default:
        state.value = 'ready'
    }
  } catch (error) {
    state.value = 'error'
    message.value = error && error.message ? error.message : '加载失败'
  }
}

const route = router.currentRoute

const archiveGroups = computed(() => {
  const groups = new Map()
  for (const item of posts.value) {
    const date = item.firstPublishedAt ? new Date(item.firstPublishedAt) : null
    if (!date || Number.isNaN(date.getTime())) continue
    const key = `${date.getFullYear()}-${date.getMonth() + 1}`
    if (!groups.has(key)) {
      groups.set(key, {
        key,
        year: date.getFullYear(),
        month: date.getMonth() + 1,
        posts: [],
      })
    }
    groups.get(key).posts.push(item)
  }
  return [...groups.values()]
})

// ---------------------------------------------------------------------------
// 动作
// ---------------------------------------------------------------------------

function go(targetPage) {
  if (targetPage < 1 || targetPage > pager.value.totalPages) return
  router.push({ path: route.value.path, query: { ...route.value.query, page: String(targetPage) } })
}

function search(value) {
  const q = String(value || '').trim()
  router.push({ path: '/search', query: q ? { q } : {} })
}

function isNavActive(to) {
  if (to === '/') return route.value.path === '/'
  return route.value.path === to || route.value.path.startsWith(`${to}/`)
}

function applyColorMode(mode) {
  const resolved = mode === 'system'
    ? (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
    : mode
  colorMode.value = resolved
  document.documentElement.dataset.colorMode = resolved
}

function setColorMode(mode) {
  if (mode === 'system') {
    localStorage.removeItem('sn.blog.colorMode')
  } else {
    localStorage.setItem('sn.blog.colorMode', mode)
  }
  applyColorMode(mode)
}

function toggleColorMode() {
  setColorMode(colorMode.value === 'dark' ? 'light' : 'dark')
}

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

function formatDate(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : dateFormatter.format(date)
}

// ---------------------------------------------------------------------------
// 灯箱
// ---------------------------------------------------------------------------

let lightboxEl = null

function closeLightbox() {
  if (!lightboxEl) return
  lightboxEl.remove()
  lightboxEl = null
  document.removeEventListener('keydown', onLightboxKey)
}

function onLightboxKey(event) {
  if (event.key === 'Escape') closeLightbox()
}

function openLightbox(src, alt) {
  if (!src) return
  closeLightbox()
  const wrapper = document.createElement('div')
  wrapper.className = 'bc-lightbox'
  wrapper.addEventListener('click', closeLightbox)
  const image = document.createElement('img')
  image.src = src
  image.alt = alt || ''
  wrapper.appendChild(image)
  document.body.appendChild(wrapper)
  lightboxEl = wrapper
  document.addEventListener('keydown', onLightboxKey)
}

// ---------------------------------------------------------------------------
// 组件词汇表
// ---------------------------------------------------------------------------

const BlogPostCard = {
  props: { post: { type: Object, required: true } },
  setup() {
    return { formatDate }
  },
  template: `
    <article class="bc-card" data-blog-post-card
             :data-pinned="post.pinned ? '1' : '0'"
             :data-has-cover="post.coverUrl ? '1' : '0'">
      <a v-if="post.coverUrl" class="bc-card-cover" :href="'/posts/' + post.slug">
        <img :src="post.coverUrl" :alt="post.title" loading="lazy" />
      </a>
      <div class="bc-card-body">
        <div class="bc-card-meta">
          <span v-if="post.pinned" class="bc-card-pin">置顶</span>
          <time>{{ formatDate(post.firstPublishedAt) }}</time>
          <span>约 {{ post.readingMinutes }} 分钟</span>
        </div>
        <h2 class="bc-card-title"><a :href="'/posts/' + post.slug">{{ post.title }}</a></h2>
        <p class="bc-card-summary">{{ post.summary }}</p>
        <div v-if="post.tags && post.tags.length" class="bc-card-tags">
          <blog-tag-chip v-for="item in post.tags.slice(0, 3)" :key="item.id" :tag="item"></blog-tag-chip>
        </div>
      </div>
    </article>
  `,
}

const BlogPager = {
  props: { pager: { type: Object, required: true } },
  emits: ['go'],
  template: `
    <nav v-if="pager.totalPages > 1" class="bc-pager" aria-label="分页">
      <button type="button" :disabled="!pager.hasPrev" @click="$emit('go', pager.page - 1)">上一页</button>
      <span>第 {{ pager.page }} / {{ pager.totalPages }} 页</span>
      <button type="button" :disabled="!pager.hasNext" @click="$emit('go', pager.page + 1)">下一页</button>
    </nav>
  `,
}

const BlogMarkdown = {
  props: { source: { type: String, default: '' } },
  setup(props) {
    const html = computed(() => renderMarkdown(props.source))
    return { html }
  },
  template: `<div class="bc-prose" data-blog-prose v-html="html"></div>`,
}

const BlogToc = {
  setup() {
    const items = ref([])
    const activeId = ref('')
    let observer = null

    async function build() {
      await nextTick()
      const headings = [...document.querySelectorAll('.bc-prose h2, .bc-prose h3')]
      items.value = headings.map((heading) => ({
        id: heading.id,
        text: heading.textContent || '',
        depth: heading.tagName === 'H3' ? 3 : 2,
      }))
      if (observer) observer.disconnect()
      if (!headings.length) return
      observer = new IntersectionObserver((entries) => {
        const visible = entries
          .filter((entry) => entry.isIntersecting)
          .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)
        if (visible.length) activeId.value = visible[0].target.id
      }, { rootMargin: '-88px 0px -70% 0px', threshold: 0 })
      headings.forEach((heading) => observer.observe(heading))
    }

    watch([contentVersion, () => route.value.fullPath], build, { immediate: true })
    onBeforeUnmount(() => observer && observer.disconnect())

    function jump(event, id) {
      event.preventDefault()
      const target = document.getElementById(id)
      if (target) {
        target.scrollIntoView({ behavior: 'smooth', block: 'start' })
        history.replaceState(null, '', `#${id}`)
      }
    }

    return { items, activeId, jump }
  },
  template: `
    <ul class="bc-toc">
      <li v-for="item in items" :key="item.id" :class="'depth-' + item.depth">
        <a :href="'#' + item.id" :class="{ active: activeId === item.id }"
           @click="jump($event, item.id)">{{ item.text }}</a>
      </li>
    </ul>
  `,
}

const BlogTagChip = {
  props: { tag: { type: Object, required: true } },
  template: `<a class="bc-tag" :href="'/tags/' + tag.id"># {{ tag.name }}</a>`,
}

const BlogTagCloud = {
  setup() {
    const rows = ref([])
    const failed = ref(false)
    onMounted(async () => {
      try {
        rows.value = await api('/api/blog/tags')
        if (!rows.value.length) message.value = '暂无标签'
      } catch {
        failed.value = true
      }
    })
    return { rows, failed }
  },
  template: `
    <div v-if="failed" class="bc-state" data-state="error">标签加载失败</div>
    <div v-else-if="!rows.length" class="bc-state" data-state="empty">暂无标签</div>
    <div v-else class="bc-tag-cloud">
      <a v-for="item in rows" :key="item.id" class="bc-tag" :href="'/tags/' + item.id">
        {{ item.name }} <span class="bc-tag-count">{{ item.count }}</span>
      </a>
    </div>
  `,
}

const BlogSearchBox = {
  props: { value: { type: String, default: '' } },
  emits: ['search'],
  setup(props, { emit }) {
    const text = ref(props.value)
    watch(() => props.value, (next) => { text.value = next })
    function submit() {
      emit('search', text.value)
    }
    return { text, submit }
  },
  template: `
    <form class="bc-search" role="search" @submit.prevent="submit">
      <input v-model="text" type="search" placeholder="输入关键词" aria-label="搜索关键词" />
      <button type="submit">搜索</button>
    </form>
  `,
}

const BlogThemeToggle = {
  setup() {
    return { colorMode, toggleColorMode }
  },
  template: `
    <button type="button" class="bc-theme-toggle"
            :aria-label="colorMode === 'dark' ? '切换到浅色' : '切换到深色'"
            @click="toggleColorMode">
      <svg v-if="colorMode === 'dark'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
        <circle cx="12" cy="12" r="4"></circle>
        <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"></path>
      </svg>
      <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
        <path d="M21 12.8A8.5 8.5 0 1 1 11.2 3a6.6 6.6 0 0 0 9.8 9.8Z"></path>
      </svg>
    </button>
  `,
}

const BlogImage = {
  props: {
    src: { type: String, required: true },
    alt: { type: String, default: '' },
  },
  setup(props) {
    return { open: () => openLightbox(props.src, props.alt) }
  },
  template: `
    <button type="button" class="bc-image" @click="open">
      <img :src="src" :alt="alt" loading="lazy" />
    </button>
  `,
}

const BlogBackToTop = {
  setup() {
    const visible = ref(false)
    function onScroll() {
      visible.value = window.scrollY > 640
    }
    onMounted(() => {
      onScroll()
      window.addEventListener('scroll', onScroll, { passive: true })
    })
    onBeforeUnmount(() => window.removeEventListener('scroll', onScroll))
    return { visible, scrollToTop }
  },
  template: `
    <button type="button" class="bc-back-to-top" :data-visible="visible ? '1' : '0'"
            aria-label="回到顶部" @click="scrollToTop">↑</button>
  `,
}

const BlogReadingProgress = {
  setup() {
    const progress = ref(0)
    function onScroll() {
      const height = document.documentElement.scrollHeight - window.innerHeight
      progress.value = height > 0 ? Math.min(1, window.scrollY / height) : 0
    }
    onMounted(() => {
      onScroll()
      window.addEventListener('scroll', onScroll, { passive: true })
      window.addEventListener('resize', onScroll)
    })
    onBeforeUnmount(() => {
      window.removeEventListener('scroll', onScroll)
      window.removeEventListener('resize', onScroll)
    })
    return { progress }
  },
  template: `<div class="bc-reading-progress" :style="{ transform: 'scaleX(' + progress + ')' }"></div>`,
}

const BlogState = {
  props: {
    state: { type: String, default: 'loading' },
    message: { type: String, default: '' },
  },
  template: `
    <div class="bc-state" :data-state="state">
      <template v-if="state === 'loading'">
        <div class="bc-skeleton"></div>
        <div class="bc-skeleton" style="height: 96px"></div>
      </template>
      <template v-else-if="state === 'error'">{{ message || '加载失败，请稍后重试' }}</template>
      <template v-else-if="state === 'empty'">{{ message || '还没有内容' }}</template>
    </div>
  `,
}

// ---------------------------------------------------------------------------
// 应用
// ---------------------------------------------------------------------------

const mountEl = document.getElementById('app')
const template = mountEl ? mountEl.innerHTML : '<div></div>'

const app = createApp({
  template,
  setup() {
    const site = ref(BOOT.site || {})
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

app.component('blog-post-card', BlogPostCard)
app.component('blog-pager', BlogPager)
app.component('blog-markdown', BlogMarkdown)
app.component('blog-toc', BlogToc)
app.component('blog-tag-chip', BlogTagChip)
app.component('blog-tag-cloud', BlogTagCloud)
app.component('blog-archives', {
  setup() {
    const rows = ref([])
    onMounted(async () => {
      try {
        rows.value = await api('/api/blog/archives')
      } catch {
        rows.value = []
      }
    })
    return { rows }
  },
  template: `
    <ul class="archive-index">
      <li v-for="item in rows" :key="item.year + '-' + item.month">
        {{ item.year }} 年 {{ item.month }} 月 <span>{{ item.count }}</span>
      </li>
    </ul>
  `,
})
app.component('blog-search-box', BlogSearchBox)
app.component('blog-theme-toggle', BlogThemeToggle)
app.component('blog-image', BlogImage)
app.component('blog-back-to-top', BlogBackToTop)
app.component('blog-reading-progress', BlogReadingProgress)
app.component('blog-state', BlogState)

app.use(router)

// 站内链接交给 history 路由，保留原生 <a> 语义
document.addEventListener('click', (event) => {
  const anchor = event.target && event.target.closest ? event.target.closest('a[href]') : null
  if (!anchor) return
  if (anchor.target || anchor.hasAttribute('download')) return
  if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  const url = new URL(anchor.href, location.href)
  if (url.origin !== location.origin) return
  if (url.pathname.startsWith('/blog/') || url.pathname.startsWith('/api/')) return
  event.preventDefault()
  router.push(url.pathname + url.search + url.hash)
})

// 系统明暗变化：仅在用户未手动覆盖时跟随
const media = window.matchMedia('(prefers-color-scheme: dark)')
media.addEventListener('change', () => {
  if (!localStorage.getItem('sn.blog.colorMode')) applyColorMode('system')
})

router.isReady().then(() => {
  app.mount(mountEl)
})

// 供管理端预览 iframe 通信（docs/blog-runtime-theme.md §7.2）
const ADMIN_ORIGIN = BOOT.adminOrigin || '*'

function validateTheme(html, css) {
  const errors = []
  const source = String(html || '')
  const lower = source.toLowerCase()

  function lineOf(index) {
    return index < 0 ? 0 : source.slice(0, index).split('\n').length
  }

  if (!lower.includes('id="app"') && !lower.includes("id='app'")) {
    errors.push({ line: 0, message: '缺少 id="app" 根节点' })
  }

  const forbidden = [
    ['<script', '禁止 <script>'],
    ['v-html', '禁止 v-html'],
    ['javascript:', '禁止 javascript: 协议'],
    ['window.', '禁止访问 window'],
    ['document.', '禁止访问 document'],
    ['localstorage', '禁止访问 localStorage'],
    ['fetch(', '禁止调用 fetch'],
    ['eval(', '禁止调用 eval'],
    ['import(', '禁止动态 import'],
  ]
  for (const [pattern, text] of forbidden) {
    const index = lower.indexOf(pattern)
    if (index >= 0) errors.push({ line: lineOf(index), message: text })
  }

  if (new Blob([source]).size > 64 * 1024) {
    errors.push({ line: 0, message: '主题 HTML 超出 64KB 上限' })
  }

  const style = String(css || '')
  if (new Blob([style]).size > 256 * 1024) {
    errors.push({ line: 0, message: '主题 CSS 超出 256KB 上限' })
  }
  if (/@import/i.test(style)) {
    errors.push({ line: 0, message: '主题 CSS 禁止 @import' })
  }

  return { ok: errors.length === 0, errors }
}

window.BlogRuntime = { contract: 1, validateTheme }

function reply(payload) {
  if (window.parent && window.parent !== window) {
    window.parent.postMessage(payload, ADMIN_ORIGIN)
  }
}

window.addEventListener('message', (event) => {
  if (ADMIN_ORIGIN !== '*' && event.origin !== ADMIN_ORIGIN) return
  const data = event.data
  if (!data || typeof data !== 'object') return

  if (data.type === 'blog-preview:color-mode' && data.mode) {
    setColorMode(data.mode)
  } else if (data.type === 'blog-preview:scroll-to' && data.selector) {
    const target = document.querySelector(data.selector)
    if (target) target.scrollIntoView({ behavior: 'smooth', block: 'start' })
  } else if (data.type === 'blog-preview:validate') {
    const result = validateTheme(data.html ?? template, data.css ?? '')
    reply({ type: 'blog-preview:validated', id: data.id, ok: result.ok, errors: result.errors })
  }
})

reply({ type: 'blog-preview:ready' })

