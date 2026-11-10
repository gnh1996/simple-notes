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

export interface SlotField {
  /** 作用域名 */
  readonly name: string
  /** 引用 CONTRACT_TYPES 的键；基本类型用 'string' / 'number' / 'boolean' */
  readonly shape: string
  readonly optional?: boolean
  readonly note?: string
}

export interface SlotEntry {
  readonly name: string
  /** slot 作用域向主题暴露的变量名（必须与 scope 的 name 一致） */
  readonly provides: readonly string[]
  /** 带类型与语义的作用域字段；供 theme-contract.json / 校验 / AI 提示词消费 */
  readonly scope: readonly SlotField[]
}

export interface FunctionEntry {
  readonly signature: string
}

export interface ComponentEntry {
  readonly props: readonly string[]
  /** props 的子集；主题未传时组件无法正常工作 */
  readonly requiredProps: readonly string[]
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
  /** 可选：最小用法示例；当前未使用，保留给 cookbook 扩展 */
  readonly example?: string
}

export const CONTRACT_DATA = {
  site: { shape: 'Site', routes: 'all' },
  nav: { shape: 'NavItem[]', routes: 'all' },
  route: { shape: 'RouteView', routes: 'all' },
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
  listSort: {
    shape: 'ListSort',
    routes: 'all',
    note: '当前列表 / 详情上下文解析后的排序参数（只读）',
  },
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
  postDate: { signature: '(post: PostSummary): string' },
} as const satisfies Record<string, FunctionEntry>

export const CONTRACT_ACTIONS = {
  go: { signature: '(page: number): void' },
  search: { signature: '(keyword: string): void' },
  setColorMode: { signature: "(mode: 'light' | 'dark' | 'system'): void" },
  toggleColorMode: { signature: '(): void' },
  scrollToTop: { signature: '(): void' },
  openLightbox: { signature: '(src: string, alt?: string): void' },
} as const satisfies Record<string, FunctionEntry>

/**
 * 模板表达式的“语言层”契约（docs/theme-contract.md §5）。
 *
 * 这里只登记纯数据；构建期 / 运行时 / 后端三处扫描器共用同一份。
 * 目的：把“Vue 模板里允许写什么”从扫描器实现提升为可投影、可测试的契约。
 */
export const CONTRACT_EXPRESSION = {
  /** Vue 模板可用的纯计算全局；不含 DOM / 网络 / 函数构造入口 */
  globals: [
    'Math',
    'Number',
    'String',
    'Boolean',
    'Array',
    'Object',
    'JSON',
    'Date',
    'parseInt',
    'parseFloat',
    'isNaN',
    'isFinite',
    'encodeURI',
    'encodeURIComponent',
    'decodeURI',
    'decodeURIComponent',
  ],
  /** 事件表达式里允许的编译器局部变量 */
  eventLocals: ['$event'],
  /** `$event` 允许的后缀（窄形状白名单，禁止任意成员访问） */
  eventLocalShapes: [
    'target.value',
    'target.checked',
    'key',
    'code',
    'preventDefault()',
    'stopPropagation()',
  ],
  /** 事件修饰符（不参与自由标识符扫描） */
  eventModifiers: [
    'stop',
    'prevent',
    'once',
    'self',
    'capture',
    'passive',
    'enter',
    'tab',
    'esc',
    'space',
    'up',
    'down',
    'left',
    'right',
    'delete',
  ],
  /** v-model 修饰符 */
  modelModifiers: ['lazy', 'number', 'trim'],
  /** 组件上允许透传的原生 DOM 事件 */
  nativeEvents: [
    'click',
    'dblclick',
    'input',
    'change',
    'submit',
    'reset',
    'keydown',
    'keyup',
    'keypress',
    'focus',
    'blur',
    'focusin',
    'focusout',
    'mouseenter',
    'mouseleave',
    'mouseover',
    'mouseout',
    'mousedown',
    'mouseup',
    'mousemove',
    'contextmenu',
    'wheel',
    'scroll',
    'touchstart',
    'touchend',
    'touchmove',
    'paste',
    'copy',
    'cut',
  ],
  /** 允许的内置组件（只放行纯展示 / 分组） */
  builtinComponents: ['Transition', 'TransitionGroup'],
  /** 已知但未放行的内置组件（用于同时拒绝大写与 kebab 写法） */
  deniedBuiltinComponents: ['Teleport', 'Suspense', 'KeepAlive', 'Fragment', 'component'],
} as const

export const CONTRACT_COMPONENTS = {
  'blog-post-card': {
    props: ['post'],
    requiredProps: ['post'],
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
    slots: [
      {
        name: 'root',
        provides: ['post'],
        scope: [{ name: 'post', shape: 'PostSummary' }],
      },
      {
        name: 'default',
        provides: ['post'],
        scope: [{ name: 'post', shape: 'PostSummary' }],
      },
    ],
  },
  'blog-pager': {
    props: ['pager'],
    requiredProps: ['pager'],
    events: ['go'],
    selfFetch: false,
    parts: ['bc-pager'],
    slots: [
      {
        name: 'root',
        provides: ['pager', 'go', 'pages'],
        scope: [
          { name: 'pager', shape: 'Pager' },
          { name: 'go', shape: '(page: number) => void' },
          { name: 'pages', shape: 'number[]' },
        ],
      },
    ],
  },
  'blog-markdown': {
    props: ['source'],
    requiredProps: [],
    events: [],
    selfFetch: false,
    parts: ['bc-prose'],
    slots: [],
  },
  'blog-toc': {
    props: [],
    requiredProps: [],
    events: [],
    selfFetch: false,
    parts: ['bc-toc'],
    slots: [
      {
        name: 'root',
        provides: ['items', 'activeId', 'scrollTo'],
        scope: [
          { name: 'items', shape: 'TocItem[]' },
          { name: 'activeId', shape: 'string' },
          { name: 'scrollTo', shape: '(id: string) => void' },
        ],
      },
    ],
  },
  'blog-tag-chip': {
    props: ['tag'],
    requiredProps: ['tag'],
    events: [],
    selfFetch: false,
    parts: ['bc-tag'],
    slots: [
      {
        name: 'root',
        provides: ['tag'],
        scope: [{ name: 'tag', shape: 'TagBrief' }],
      },
    ],
  },
  'blog-tag-cloud': {
    props: [],
    requiredProps: [],
    events: [],
    selfFetch: true,
    parts: ['bc-tag-cloud', 'bc-tag', 'bc-tag-count', 'bc-state'],
    slots: [
      {
        name: 'root',
        provides: ['tags', 'state', 'message'],
        scope: [
          { name: 'tags', shape: 'Tag[]' },
          { name: 'state', shape: 'string' },
          { name: 'message', shape: 'string' },
        ],
      },
    ],
  },
  'blog-archives': {
    props: [],
    requiredProps: [],
    events: [],
    selfFetch: true,
    parts: ['archive-index', 'bc-state'],
    slots: [
      {
        name: 'root',
        provides: ['archives', 'state', 'message'],
        scope: [
          { name: 'archives', shape: 'Archive[]' },
          { name: 'state', shape: 'string' },
          { name: 'message', shape: 'string' },
        ],
      },
    ],
  },
  'blog-search-box': {
    props: ['value'],
    requiredProps: [],
    events: ['search'],
    selfFetch: false,
    parts: ['bc-search'],
    slots: [
      {
        name: 'root',
        provides: ['value', 'search'],
        scope: [
          { name: 'value', shape: 'string' },
          { name: 'search', shape: '(value: string) => void' },
        ],
      },
    ],
  },
  'blog-theme-toggle': {
    props: [],
    requiredProps: [],
    events: [],
    selfFetch: false,
    parts: ['bc-theme-toggle'],
    slots: [
      {
        name: 'root',
        provides: ['colorMode', 'toggle', 'set'],
        scope: [
          { name: 'colorMode', shape: "'light' | 'dark'" },
          { name: 'toggle', shape: '() => void' },
          { name: 'set', shape: "(mode: 'light' | 'dark' | 'system') => void" },
        ],
      },
    ],
  },
  'blog-image': {
    props: ['src', 'alt'],
    requiredProps: ['src'],
    events: [],
    selfFetch: false,
    parts: ['bc-image'],
    slots: [
      {
        name: 'root',
        provides: ['src', 'alt', 'open'],
        scope: [
          { name: 'src', shape: 'string' },
          { name: 'alt', shape: 'string' },
          { name: 'open', shape: '() => void' },
        ],
      },
    ],
  },
  'blog-back-to-top': {
    props: [],
    requiredProps: [],
    events: [],
    selfFetch: false,
    parts: ['bc-back-to-top'],
    slots: [
      {
        name: 'root',
        provides: ['visible', 'scroll'],
        scope: [
          { name: 'visible', shape: 'boolean' },
          { name: 'scroll', shape: '() => void' },
        ],
      },
    ],
  },
  'blog-reading-progress': {
    props: [],
    requiredProps: [],
    events: [],
    selfFetch: false,
    parts: ['bc-reading-progress'],
    slots: [
      {
        name: 'root',
        provides: ['progress'],
        scope: [{ name: 'progress', shape: 'number' }],
      },
    ],
  },
  'blog-state': {
    props: ['state', 'message'],
    requiredProps: [],
    events: [],
    selfFetch: false,
    parts: ['bc-state', 'bc-skeleton'],
    slots: [
      {
        name: 'root',
        provides: ['state', 'message'],
        scope: [
          { name: 'state', shape: 'string' },
          { name: 'message', shape: 'string' },
        ],
      },
    ],
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
  'v-slot',
] as const

/** 主题可见的路由名 / path / query；与 runtime.ts 保持一致（check:contract 守护）。 */
export const CONTRACT_ROUTES = {
  home: { path: '/', query: [] },
  post: { path: '/posts/:slug', query: [] },
  tags: { path: '/tags', query: ['page', 'size', 'sortBy', 'direction', 'pinnedFirst'] },
  'tag-posts': { path: '/tags/:id', query: ['page', 'size', 'sortBy', 'direction', 'pinnedFirst'] },
  archives: {
    path: '/archives',
    query: ['year', 'month', 'page', 'size', 'sortBy', 'direction', 'pinnedFirst'],
  },
  search: { path: '/search', query: ['q', 'page', 'size', 'sortBy', 'direction', 'pinnedFirst'] },
  page: { path: '/p/:slug', alias: ['/about'], query: [] },
  'not-found': { path: '/:pathMatch(.*)*', query: [] },
} as const

/** 主题里可直接拼接的 URL 形态；校验与提示词使用。 */
export const CONTRACT_URLS = {
  post: '/posts/{slug}',
  tag: '/tags/{id}',
  archives: '/archives?year={year}&month={month}',
  search: '/search?q={q}',
  page: '/p/{slug}',
} as const

/** 一次性取用：构建产物 / 校验器 / 文档生成的统一形状。 */
export const CONTRACT = {
  version: RUNTIME_CONTRACT,
  data: CONTRACT_DATA,
  helpers: CONTRACT_HELPERS,
  actions: CONTRACT_ACTIONS,
  components: CONTRACT_COMPONENTS,
  expression: CONTRACT_EXPRESSION,
  globalParts: CONTRACT_GLOBAL_PARTS,
  cssVars: CONTRACT_CSS_VARS,
  directives: CONTRACT_DIRECTIVES,
  routes: CONTRACT_ROUTES,
  urls: CONTRACT_URLS,
} as const
