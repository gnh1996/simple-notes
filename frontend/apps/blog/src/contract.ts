/**
 * 主题契约：主题可依赖的**唯一**接口面（docs/blog-runtime-theme.md §3）。
 *
 * 规则：
 * - 本文件是唯一登记处；文档、admin 说明、未来的 agent 上下文都从这里派生；
 * - runtime 实际暴露给模板的成员必须与 `data` / `helpers` / `actions` 完全一致，
 *   由 `pnpm check:contract` 守护（登记了没实现、实现了没登记，都会失败）；
 * - 只增不改；删除或改语义 = 升 `version`（破坏性变更）。
 *
 * 本文件刻意保持「纯数据、无 import、可擦除语法」：Node 可直接 import 它来生成
 * `theme-contract.json`（构建产物，见 `scripts/theme-contract.mjs`）。
 */

/** 运行时契约版本：与后端 `ThemeService.RUNTIME_CONTRACT` 必须同步。 */
export const RUNTIME_CONTRACT = 1

export interface DataEntry {
  /** 数据形状（类型名，定义见 `types.ts` / 文档 §3.2） */
  readonly shape: string
  /** 出现页面（路由 name）；`all` = 全部页面 */
  readonly routes: readonly string[] | 'all'
  /** 主题是否可写（目前只有 `ui`）；默认只读 */
  readonly writable?: boolean
  /** 语义补充，进入 `theme-contract.json` 供 agent / 文档消费 */
  readonly note?: string
}

export interface SlotEntry {
  readonly name: string
  /** slot 作用域向主题暴露的变量名 */
  readonly provides: readonly string[]
}

export interface FunctionEntry {
  readonly signature: string
}

export interface ComponentEntry {
  readonly props: readonly string[]
  readonly events: readonly string[]
  /** 组件自取数据（放上去即产生请求） */
  readonly selfFetch: boolean
  /**
   * 组件对外暴露的样式 class（公开接口，只增不改）。
   * 主题只能通过这些 class 定制组件外观；组件内部结构变化不得改动已登记的 class。
   */
  readonly parts: readonly string[]
  /** 具名 slot（公开接口，只增不改）；为空表示组件不接受覆盖 */
  readonly slots: readonly SlotEntry[]
}

export const CONTRACT_DATA = {
  site: { shape: 'Site', routes: 'all' },
  nav: { shape: 'NavItem[]', routes: 'all' },
  route: { shape: 'RouteLocation', routes: 'all' },
  state: { shape: "'loading' | 'empty' | 'error' | 'ready'", routes: 'all' },
  message: { shape: 'string', routes: 'all' },
  colorMode: { shape: "'light' | 'dark'", routes: 'all' },
  posts: {
    shape: 'PostSummary[]',
    routes: ['home', 'tags', 'tag-posts', 'archives', 'search'],
  },
  pager: {
    shape: 'Pager',
    routes: ['home', 'tags', 'tag-posts', 'archives', 'search'],
  },
  post: { shape: 'PostDetail | null', routes: ['post'] },
  prevPost: { shape: 'PostSummary | null', routes: ['post'] },
  nextPost: { shape: 'PostSummary | null', routes: ['post'] },
  tags: { shape: 'Tag[]', routes: ['tags', 'tag-posts'] },
  tag: { shape: 'Tag | null', routes: ['tag-posts'] },
  archives: { shape: 'Archive[]', routes: ['archives'] },
  archiveGroups: { shape: 'ArchiveGroup[]', routes: ['archives'] },
  keyword: { shape: 'string', routes: ['search'] },
  searched: { shape: 'boolean', routes: ['search'] },
  page: { shape: 'SitePage | null', routes: ['page'] },
  ui: {
    shape: 'Record<string, unknown>',
    routes: 'all',
    writable: true,
    note: '主题自有 UI 状态（抽屉 / 标签页 / 筛选），可 v-model；路由切换时清空，不持久化',
  },
} as const satisfies Record<string, DataEntry>

export const CONTRACT_HELPERS = {
  isNavActive: { signature: '(to: string): boolean' },
  formatDate: { signature: '(value: string | null | undefined): string' },
} as const satisfies Record<string, FunctionEntry>

export const CONTRACT_ACTIONS = {
  go: { signature: '(page: number): void' },
  search: { signature: '(keyword: string): void' },
  setColorMode: { signature: "(mode: 'light' | 'dark' | 'system'): void" },
  toggleColorMode: { signature: '(): void' },
  scrollToTop: { signature: '(): void' },
  openLightbox: { signature: '(src: string, alt?: string): void' },
} as const satisfies Record<string, FunctionEntry>

export const CONTRACT_COMPONENTS = {
  'blog-post-card': {
    props: ['post'],
    events: [],
    selfFetch: false,
    parts: [
      'bc-card',
      'bc-card-cover',
      'bc-card-body',
      'bc-card-meta',
      'bc-card-pin',
      'bc-card-title',
      'bc-card-summary',
      'bc-card-tags',
    ],
    slots: [{ name: 'default', provides: ['post'] }],
  },
  'blog-pager': { props: ['pager'], events: ['go'], selfFetch: false, parts: ['bc-pager'], slots: [] },
  'blog-markdown': { props: ['source'], events: [], selfFetch: false, parts: ['bc-prose'], slots: [] },
  'blog-toc': { props: [], events: [], selfFetch: false, parts: ['bc-toc'], slots: [] },
  'blog-tag-chip': { props: ['tag'], events: [], selfFetch: false, parts: ['bc-tag'], slots: [] },
  'blog-tag-cloud': {
    props: [],
    events: [],
    selfFetch: true,
    parts: ['bc-tag-cloud', 'bc-tag', 'bc-tag-count', 'bc-state'],
    slots: [],
  },
  'blog-archives': {
    props: [],
    events: [],
    selfFetch: true,
    parts: ['archive-index', 'bc-state'],
    slots: [],
  },
  'blog-search-box': {
    props: ['value'],
    events: ['search'],
    selfFetch: false,
    parts: ['bc-search'],
    slots: [],
  },
  'blog-theme-toggle': {
    props: [],
    events: [],
    selfFetch: false,
    parts: ['bc-theme-toggle'],
    slots: [],
  },
  'blog-image': { props: ['src', 'alt'], events: [], selfFetch: false, parts: ['bc-image'], slots: [] },
  'blog-back-to-top': {
    props: [],
    events: [],
    selfFetch: false,
    parts: ['bc-back-to-top'],
    slots: [],
  },
  'blog-reading-progress': {
    props: [],
    events: [],
    selfFetch: false,
    parts: ['bc-reading-progress'],
    slots: [],
  },
  'blog-state': {
    props: ['state', 'message'],
    events: [],
    selfFetch: false,
    parts: ['bc-state', 'bc-skeleton'],
    slots: [],
  },
} as const satisfies Record<string, ComponentEntry>

/**
 * 不属于任何组件、但由 runtime 渲染的公开 class（如灯箱）。
 * 主题可覆盖，但改样式不得破坏 runtime 行为。
 */
export const CONTRACT_GLOBAL_PARTS = ['bc-lightbox'] as const

/** 主题可覆盖的 CSS 变量（`theme.overrides` 只允许这些键）。 */
export const CONTRACT_CSS_VARS = [
  '--blog-accent',
  '--blog-accent-contrast',
  '--blog-bg',
  '--blog-surface',
  '--blog-text',
  '--blog-text-muted',
  '--blog-border',
  '--blog-radius',
  '--blog-font-sans',
  '--blog-font-mono',
  '--blog-prose-size',
  '--blog-prose-line-height',
  '--blog-content-width',
  '--blog-gap',
  '--blog-code-bg',
] as const

/** 主题模板允许的指令（渲染引擎是 Vue，但这是**契约子集**，不是完整 Vue）。 */
export const CONTRACT_DIRECTIVES = [
  'v-if',
  'v-else-if',
  'v-else',
  'v-for',
  'v-show',
  'v-model',
  'v-text',
  'v-once',
  'v-cloak',
  'v-bind',
  'v-on',
] as const

/** 一次性取用：构建产物 / 校验器 / 文档生成的统一形状。 */
export const CONTRACT = {
  version: RUNTIME_CONTRACT,
  data: CONTRACT_DATA,
  helpers: CONTRACT_HELPERS,
  actions: CONTRACT_ACTIONS,
  components: CONTRACT_COMPONENTS,
  globalParts: CONTRACT_GLOBAL_PARTS,
  cssVars: CONTRACT_CSS_VARS,
  directives: CONTRACT_DIRECTIVES,
} as const
