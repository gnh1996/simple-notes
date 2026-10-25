import {
  computed,
  defineComponent,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
  type Component,
  type PropType,
} from 'vue'

import { api } from './api'
import { formatDate } from './format'
import { openLightbox } from './lightbox'
import { renderMarkdown } from './markdown'
import {
  colorMode,
  contentVersion,
  currentPath,
  toggleColorMode,
} from './store'
import type { Archive, Pager, PostSummary, Tag } from './types'

const BlogPostCard = defineComponent({
  name: 'BlogPostCard',
  props: {
    post: { type: Object as PropType<PostSummary>, required: true },
  },
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
})

const BlogPager = defineComponent({
  name: 'BlogPager',
  props: {
    pager: { type: Object as PropType<Pager>, required: true },
  },
  emits: ['go'],
  template: `
    <nav v-if="pager.totalPages > 1" class="bc-pager" aria-label="分页">
      <button type="button" :disabled="!pager.hasPrev" @click="$emit('go', pager.page - 1)">上一页</button>
      <span>第 {{ pager.page }} / {{ pager.totalPages }} 页</span>
      <button type="button" :disabled="!pager.hasNext" @click="$emit('go', pager.page + 1)">下一页</button>
    </nav>
  `,
})

const BlogMarkdown = defineComponent({
  name: 'BlogMarkdown',
  props: {
    source: { type: String, default: '' },
  },
  setup(props) {
    const html = computed(() => renderMarkdown(props.source))
    return { html }
  },
  template: `<div class="bc-prose" data-blog-prose v-html="html"></div>`,
})

const BlogToc = defineComponent({
  name: 'BlogToc',
  setup() {
    const items = ref<Array<{ id: string; text: string; depth: number }>>([])
    const activeId = ref('')
    let observer: IntersectionObserver | null = null

    async function build(): Promise<void> {
      await nextTick()
      const headings = [...document.querySelectorAll<HTMLElement>('.bc-prose h2, .bc-prose h3')]
      items.value = headings.map((heading) => ({
        id: heading.id,
        text: heading.textContent || '',
        depth: heading.tagName === 'H3' ? 3 : 2,
      }))
      if (observer) {
        observer.disconnect()
      }
      if (!headings.length) {
        return
      }
      observer = new IntersectionObserver(
        (entries) => {
          const visible = entries
            .filter((entry) => entry.isIntersecting)
            .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)
          if (visible.length) {
            activeId.value = (visible[0].target as HTMLElement).id
          }
        },
        { rootMargin: '-88px 0px -70% 0px', threshold: 0 },
      )
      headings.forEach((heading) => observer?.observe(heading))
    }

    watch([contentVersion, currentPath], build, { immediate: true })
    onBeforeUnmount(() => observer?.disconnect())

    function jump(event: Event, id: string): void {
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
})

const BlogTagChip = defineComponent({
  name: 'BlogTagChip',
  props: {
    tag: { type: Object as PropType<{ id: string; name: string }>, required: true },
  },
  template: `<a class="bc-tag" :href="'/tags/' + tag.id"># {{ tag.name }}</a>`,
})

const BlogTagCloud = defineComponent({
  name: 'BlogTagCloud',
  setup() {
    const rows = ref<Tag[]>([])
    const failed = ref(false)
    onMounted(async () => {
      try {
        rows.value = await api<Tag[]>('/api/blog/tags')
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
})

const BlogArchives = defineComponent({
  name: 'BlogArchives',
  setup() {
    const rows = ref<Archive[]>([])
    onMounted(async () => {
      try {
        rows.value = await api<Archive[]>('/api/blog/archives')
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

const BlogSearchBox = defineComponent({
  name: 'BlogSearchBox',
  props: {
    value: { type: String, default: '' },
  },
  emits: ['search'],
  setup(props, { emit }) {
    const text = ref(props.value)
    watch(
      () => props.value,
      (next) => {
        text.value = next
      },
    )
    function submit(): void {
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
})

const BlogThemeToggle = defineComponent({
  name: 'BlogThemeToggle',
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
})

const BlogImage = defineComponent({
  name: 'BlogImage',
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
})

const BlogBackToTop = defineComponent({
  name: 'BlogBackToTop',
  setup() {
    const visible = ref(false)
    function onScroll(): void {
      visible.value = window.scrollY > 640
    }
    function scrollToTop(): void {
      window.scrollTo({ top: 0, behavior: 'smooth' })
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
})

const BlogReadingProgress = defineComponent({
  name: 'BlogReadingProgress',
  setup() {
    const progress = ref(0)
    function onScroll(): void {
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
})

const BlogState = defineComponent({
  name: 'BlogState',
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
})

/** 组件词汇表（docs/blog-runtime-theme.md §3.4）：只增不改。 */
export const blogComponents: Record<string, Component> = {
  'blog-post-card': BlogPostCard,
  'blog-pager': BlogPager,
  'blog-markdown': BlogMarkdown,
  'blog-toc': BlogToc,
  'blog-tag-chip': BlogTagChip,
  'blog-tag-cloud': BlogTagCloud,
  'blog-archives': BlogArchives,
  'blog-search-box': BlogSearchBox,
  'blog-theme-toggle': BlogThemeToggle,
  'blog-image': BlogImage,
  'blog-back-to-top': BlogBackToTop,
  'blog-reading-progress': BlogReadingProgress,
  'blog-state': BlogState,
}
