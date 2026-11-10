# 主题契约生成物

> 自动生成，禁止手改。运行 `pnpm sync:contract`。

<!-- contract:begin -->

## 组件

### `<blog-post-card>`

- props：post*
- events：-
- slots：#root { post: PostSummary }；#default { post: PostSummary }
- parts：bc-card, bc-card-cover, bc-card-body, bc-card-meta, bc-card-pin, bc-card-title, bc-card-summary, bc-card-tags
- selfFetch：否

```html
<blog-post-card v-for="item in posts" :key="item.slug" :post="item" />
```

### `<blog-pager>`

- props：pager*
- events：go
- slots：#root { pager: Pager; go: (page: number) => void; pages: number[] }
- parts：bc-pager
- selfFetch：否

```html
<blog-pager :pager="pager" v-slot:root="{ pager, go, pages }">
  <button v-for="page in pages" :key="page" :disabled="page === pager.page" @click="go(page)">{{ page }}</button>
</blog-pager>
```

### `<blog-markdown>`

- props：source
- events：-
- slots：-
- parts：bc-prose
- selfFetch：否

```html
<blog-markdown :source="post.content" />
```

### `<blog-toc>`

- props：-
- events：-
- slots：#root { items: TocItem[]; activeId: string; scrollTo: (id: string) => void }
- parts：bc-toc
- selfFetch：否

```html
<blog-toc v-slot:root="{ items, activeId, scrollTo }">
  <a v-for="item in items" :key="item.id" :href="'#' + item.id" @click.prevent="scrollTo(item.id)">{{ item.text }}</a>
</blog-toc>
```

### `<blog-tag-chip>`

- props：tag*
- events：-
- slots：#root { tag: TagBrief }
- parts：bc-tag
- selfFetch：否

```html
<blog-tag-chip v-for="tag in post.tags" :key="tag.id" :tag="tag" />
```

### `<blog-tag-cloud>`

- props：-
- events：-
- slots：#root { tags: Tag[]; state: string; message: string }
- parts：bc-tag-cloud, bc-tag, bc-tag-count, bc-state
- selfFetch：是

```html
<blog-tag-cloud />
```

### `<blog-archives>`

- props：-
- events：-
- slots：#root { archives: Archive[]; state: string; message: string }
- parts：archive-index, bc-state
- selfFetch：是

```html
<blog-archives />
```

### `<blog-search-box>`

- props：value
- events：search
- slots：#root { value: string; search: (value: string) => void }
- parts：bc-search
- selfFetch：否

```html
<blog-search-box :value="keyword" @search="search" />
```

### `<blog-theme-toggle>`

- props：-
- events：-
- slots：#root { colorMode: 'light' | 'dark'; toggle: () => void; set: (mode: 'light' | 'dark' | 'system') => void }
- parts：bc-theme-toggle
- selfFetch：否

```html
<blog-theme-toggle v-slot:root="{ colorMode, toggle }">
  <button type="button" @click="toggle">{{ colorMode === 'dark' ? '浅色' : '深色' }}</button>
</blog-theme-toggle>
```

### `<blog-image>`

- props：src*, alt
- events：-
- slots：#root { src: string; alt: string; open: () => void }
- parts：bc-image
- selfFetch：否

```html
<blog-image :src="post.coverUrl" :alt="post.title" />
```

### `<blog-back-to-top>`

- props：-
- events：-
- slots：#root { visible: boolean; scroll: () => void }
- parts：bc-back-to-top
- selfFetch：否

```html
<blog-back-to-top v-slot:root="{ visible, scroll }">
  <button v-if="visible" type="button" @click="scroll">回到顶部</button>
</blog-back-to-top>
```

### `<blog-reading-progress>`

- props：-
- events：-
- slots：#root { progress: number }
- parts：bc-reading-progress
- selfFetch：否

```html
<blog-reading-progress v-slot:root="{ progress }">
  <div :style="{ transform: 'scaleX(' + progress + ')' }"></div>
</blog-reading-progress>
```

### `<blog-state>`

- props：state, message
- events：-
- slots：#root { state: string; message: string }
- parts：bc-state, bc-skeleton
- selfFetch：否

```html
<blog-state :state="state" :message="message" />
```

## 数据类型

```text
Archive { year: number; month: number; count: number }
ArchiveGroup { key: string; year: number; month: number; posts: PostSummary[] }
Author { name: string; avatarUrl: string | null; bio: string }
ListSort { pinnedFirst: boolean; direction: 'asc' | 'desc'; sortBy: SortField }
NavItem { key: string; label: string; to: string }
Pager { page: number; size: number; total: number; totalPages: number; hasPrev: boolean; hasNext: boolean }
PostDetail { slug: string; title: string; summary: string; coverUrl: string | null; tags: TagBrief[]; pinned: boolean; firstPublishedAt: string; lastPublishedAt: string; authoredAt: string | null; displayAt: string; readingMinutes: number; content: string; authorName: string; prevPost: PostSummary | null; nextPost: PostSummary | null }
PostSummary { slug: string; title: string; summary: string; coverUrl: string | null; tags: TagBrief[]; pinned: boolean; firstPublishedAt: string; lastPublishedAt: string; authoredAt: string | null; displayAt: string; readingMinutes: number }
RouteView { name: string | symbol | null | undefined; path: string; params: Record<string, string | string[]>; query: Record<string, string | string[] | null>; hash: string }
Site { name: string; tagline: string; description: string; logoUrl: string | null; faviconUrl: string | null; baseUrl: string | null; icp: string | null; policeRecord: string | null; author: Author; social: SocialLink[]; nav: NavItem[]; themeMode: string; themeOverrides: Record<string, string> | null; footerText: string; pageSize: number }
SitePage { id: string; slug: string; title: string; content: string; visible: boolean; sort: number; updatedAt: string }
SocialLink { type: string | null; label: string | null; url: string | null }
SortField = 'displayAt' | 'firstPublishedAt' | 'lastPublishedAt'
Tag { id: string; name: string; count: number }
TagBrief { id: string; name: string }
TocItem { id: string; text: string; depth: number }
```

## 路由

- `home` → `/`
- `post` → `/posts/:slug`
- `tags` → `/tags` query: page, size, sortBy, direction, pinnedFirst
- `tag-posts` → `/tags/:id` query: page, size, sortBy, direction, pinnedFirst
- `archives` → `/archives` query: year, month, page, size, sortBy, direction, pinnedFirst
- `search` → `/search` query: q, page, size, sortBy, direction, pinnedFirst
- `page` → `/p/:slug`
- `not-found` → `/:pathMatch(.*)*`

## URL 形态

- `post`：`/posts/{slug}`
- `tag`：`/tags/{id}`
- `archives`：`/archives?year={year}&month={month}`
- `search`：`/search?q={q}`
- `page`：`/p/{slug}`

## CSS 变量

`--blog-accent`, `--blog-accent-contrast`, `--blog-bg`, `--blog-surface`, `--blog-text`, `--blog-text-muted`, `--blog-border`, `--blog-radius`, `--blog-font-sans`, `--blog-font-mono`, `--blog-prose-size`, `--blog-prose-line-height`, `--blog-content-width`, `--blog-gap`, `--blog-code-bg`

## 指令

`v-if`, `v-else-if`, `v-else`, `v-for`, `v-show`, `v-model`, `v-text`, `v-once`, `v-cloak`, `v-bind`, `v-on`, `v-slot`

## 表达式

- 全局：`Math`, `Number`, `String`, `Boolean`, `Array`, `Object`, `JSON`, `Intl`, `Date`, `parseInt`, `parseFloat`, `isNaN`, `isFinite`, `encodeURI`, `encodeURIComponent`, `decodeURI`, `decodeURIComponent`
- 事件局部变量：`$event`
- 事件修饰符：`stop`, `prevent`, `once`, `self`, `capture`, `passive`, `enter`, `tab`, `esc`, `space`, `up`, `down`, `left`, `right`, `delete`
- v-model 修饰符：`lazy`, `number`, `trim`
- 原生事件：`click`, `dblclick`, `input`, `change`, `submit`, `reset`, `keydown`, `keyup`, `keypress`, `focus`, `blur`, `focusin`, `focusout`, `mouseenter`, `mouseleave`, `mouseover`, `mouseout`, `mousedown`, `mouseup`, `mousemove`, `contextmenu`, `wheel`, `scroll`, `touchstart`, `touchend`, `touchmove`, `paste`, `copy`, `cut`

<!-- contract:end -->
