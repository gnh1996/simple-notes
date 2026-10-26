# 博客运行时与主题化设计（Blog Runtime & Theme）

> **状态：v1.7 · 2026-10-26（主题切换收敛到主题管理，站点设置只读展示）**
> 范围：博客前台从 Vue SPA 改为「后端组装页面 + 固定运行时 + 数据化主题」；runtime 仍由 Vite 构建，但产物在 Maven 打包时进入后端 jar，与页面、API 同源提供；含站点配置、独立页面、主题管理与 agent 预览链路
> 关联：`docs/content-model.md`（内容与公开读取）、`docs/tech-stack.md`（选型）、`docs/markdown-dialect.md`（方言）、`docs/auth-rbac.md`（权限码与菜单）
> 非目标：SSR / 预渲染、评论、图片派生、RSS / sitemap、主题市场、多作者

## 0. 决策摘要

| 决策点 | 结论 |
| --- | --- |
| 前台形态 | 保留 SPA 交互，**首屏由后端组装壳**：注入站点配置 + 主题 HTML/CSS，返回整页；runtime 由同源静态资源加载 |
| 页面路由 | **归 runtime**：后端只做 history fallback（保留前缀之外任意 GET 返回同一壳），新增前端路由不改后端 |
| 运行时归属 | runtime 是**构建产物**（Vite 构建、随代码发布、可评审、入口名固定 + chunk 哈希），Maven 打包时进入 jar 的 `static/blog/`，不存数据库 |
| 主题归属 | 主题是**数据**（数据库中的不可变版本），由管理端 / agent 编辑，不发版即生效 |
| 主题构成 | `theme.html` + `theme.css`，**零主题 JS**；行为与数据由 runtime 提供 |
| 主题自由度 | **版式自由、行为受控**：整页 DOM 由主题决定；数据、动作、路由、Markdown 渲染由 runtime 决定 |
| 数据选择 | 核心数据必给；可选数据由组件自取（`v-if` 即请求开关）；页面模板可缺省，缺省回落内置默认主题 |
| 契约版本 | `runtime.contract` 整数，只增不改；主题声明 `contract`，不匹配回落默认主题 |
| 预览 | 跨源 iframe 指向 blog，短期签名 token 选中草稿版本；校验与渲染都在 iframe 内，`postMessage` 通信 |
| 应用与回滚 | 版本不可变 + 指针切换；**激活必须人工确认**，agent 只提交建议 |
| 站点配置 | KV 表 + 代码侧配置注册表；复杂值以 JSON 字符串存储 |
| 独立页面 | 独立表 `site_page`，保存即线上，不做发布快照（刻意与文章不一致） |
| 默认主题 | 唯一源 `frontend/packages/theme-starter/`；后端兜底副本由 `pnpm sync:theme` 同步并由 `pnpm check:theme` 校验 |
| 部署 | blog 与 admin **分域**；blog 为**单 jar**：页面、`/blog/**` 运行时资源与 `/api/**` 同源；不要求独立静态托管 / CDN |

---

## 1. 目标与非目标

### 1.1 目标

1. 博客前台在移动端（320 / 375 / 414）可用且精致，桌面端不退步。
2. 视觉风格可以不发版地替换：主题由管理端落地，后续可由 agent 产出。
3. 站点信息（品牌、导航、备案、页脚等）运营可改，无需改代码。
4. 升级路径可控：运行时与主题解耦，主题出错不影响兜底可用。
5. 主题作者的认知负担最小：只写 HTML 与 CSS，不写数据请求、不写 Markdown 渲染。

### 1.2 非目标

- 不做自由画布 / 低代码页面搭建；
- 不做运行时上传任意 JS / CSS 的插件机制；
- 不做 SSR / 预渲染 / SEO 优化（`title` 与 `theme-color` 等基础项保留）；
- 不做图片多尺寸派生（移动端图片靠 CSS 与上传规范控制）；
- 不做主题市场、多作者、评论。

---

## 2. 总体架构

```text
浏览器（blog.example.com）
  │  GET /  /posts/**  /tags/**  …（全部由后端 fallback 到同一壳）
  ▼
Spring 后端
  ├─ 读取 ACTIVE 主题版本 + site 配置
  ├─ 组装 HTML：注入 window.__BLOG_BOOT__、内联主题 CSS、输出主题 HTML
  └─ 返回整页（ETag + s-maxage；预览 no-store）
       ├─ /blog/runtime.js       jar 内构建产物（Vite；入口固定，chunk 带哈希）
       └─ /api/blog/**           公开 JSON 接口

runtime（浏览器）
  ├─ 挂载 #app，接管路由与数据请求
  ├─ 从壳注入数据读取 site / 主题元信息（无第二条启动路径）
  └─ 渲染主题模板（模板已由后端注入，零脚本）
```

职责边界：

| 层 | 负责 | 不负责 |
| --- | --- | --- |
| 后端 | 组装壳、注入配置、存储主题版本、签发预览 token、出公开接口、CSP / 缓存头、托管 jar 内 runtime | 渲染 Markdown、执行业务逻辑、执行主题模板 |
| runtime | 路由、取数、四态、Markdown 渲染、暗色、动作、契约校验、模板编译 | 决定版式、决定主题版本 |
| 主题 | 版式、DOM 结构、样式、条件与循环 | 发请求、写脚本、改方言、改路由 |

**关键取舍**：Markdown 渲染留在浏览器，后端不渲染。这样 `docs/content-model.md` §10「后端只存原文」的约定不被推翻，方言一致性由 runtime 单点保证。

---

## 3. 运行时契约（冻结表）

契约是主题唯一可依赖的接口。**以下三张表（数据形状 / 配置键 / CSS 变量）在实现前冻结；组件与动作可以只增不改地扩展。**

### 3.1 契约版本

```text
runtime.contract = 1
theme.contract   = N
兼容条件：runtime.contract - theme.contract <= 1
不兼容：不渲染主题，回落内置默认主题，管理端告警
```

- 只增不改；删除或改语义 = 升 major；
- 运行时至少同时支持 `N` 与 `N-1`；
- 管理端保存 / 应用 / 预览时都要做版本校验；
- **运行期双端校验**：后端按自己的 `RUNTIME_CONTRACT` 上限兜底，runtime 再按自身支持的契约校验（主题是数据，无法在构建期检查）；runtime 与后端同 jar 发布，契约常量在同一次发版中升级。

**冻结分级**（避免"什么都不能改"或"什么都能改"两种极端）：

| 级别 | 内容 | 规则 |
| --- | --- | --- |
| **L0 冻结** | 数据形状、配置键名、CSS 变量名、URL 结构、`state` 枚举 | 破坏性变更，必须升 major + 兼容窗口 + 全量回归 |
| **L1 只增** | 组件、动作、新配置键、新数据字段 | 随时可加，不升版本 |
| **L2 自由演进** | 主题包形态（几个文件 / 加 manifest / 换存储）、编辑 UI、预览协议、校验实现 | 内部实现，随迁移演进，主题无需感知 |

### 3.2 数据形状

注入入口：`window.__BLOG_BOOT__ = { site, route, themeMode }`；`colorMode`（实际生效的 light / dark）由 runtime 结合 localStorage 与系统偏好解析后暴露给模板。其余数据由 runtime 按路由取。

| 键 | 类型 | 出现页面 | 说明 |
| --- | --- | --- | --- |
| `site` | `Site` | 全部 | 站点配置，见 §3.5 |
| `nav` | `NavItem[]` | 全部 | 已过滤不可见项并按 `order` 排序 |
| `route` | `{ name, path, params, query }` | 全部 | 当前路由 |
| `state` | `'loading' \| 'empty' \| 'error' \| 'ready'` | 全部 | 四态，主题负责展示 |
| `message` | `string` | 全部 | `state = error` 时的提示文案 |
| `colorMode` | `'light' \| 'dark'` | 全部 | 实际生效值（`system` 已解析） |
| `posts` | `PostSummary[]` | 首页 / 标签 / 归档 / 搜索 | 当前页列表 |
| `pager` | `Pager` | 同 `posts` | 分页 |
| `post` | `PostDetail` | 文章详情 | 正文为 Markdown 原文 |
| `prevPost` / `nextPost` | `PostSummary \| null` | 文章详情 | 按首次发布时间排序 |
| `tags` | `Tag[]` | 标签页 | 全部标签及已发布数量 |
| `tag` | `Tag \| null` | 标签文章页 | 当前标签 |
| `archives` | `Archive[]` | 归档页 | 年月及数量 |
| `keyword` | `string` | 搜索页 | 当前关键词 |

```text
Site        { name, tagline, description, logoUrl, faviconUrl, baseUrl,
              icp, policeRecord, author: Author, social: SocialLink[], footerText }
Author      { name, avatarUrl, bio }
SocialLink  { type, label, url }
NavItem     { key, label, to, external }
PostSummary { slug, title, summary, coverUrl, tags: TagBrief[], pinned,
              firstPublishedAt, readingMinutes }
PostDetail  { slug, title, content, summary, coverUrl, tags: TagBrief[],
              authorName, firstPublishedAt, lastPublishedAt, readingMinutes }
Tag         { id, name, count }
TagBrief    { id, name }
Archive     { year, month, count }
Pager       { page, size, total, totalPages, hasPrev, hasNext }
SitePage    { slug, title, content, updatedAt }   // /p/:slug 与 /about
```

约束：

- 字段**只增不减**；新增字段必须给默认值。
- `content` 永远是 Markdown 原文，主题不得自行解析，必须用 `<blog-markdown>`。
- 字段命名固定（`camelCase`）；改名为破坏性变更。
- 首屏与站内跳转都由 runtime 在导航确认前取数（`beforeResolve`），确认后一次性写入（`afterEach`）。因此 `loading` 不是首屏必经态，**主题不要依赖骨架屏表达加载**；导航期间旧页面保持可见。

### 3.3 动作

| 动作 | 参数 | 说明 |
| --- | --- | --- |
| `go(page)` | `number` | 列表翻页 |
| `search(keyword)` | `string` | 跳转搜索 |
| `toggleColorMode()` | — | 明暗切换并持久化（localStorage） |
| `setColorMode(mode)` | `'light' \| 'dark' \| 'system'` | 显式设置 |
| `scrollToTop()` | — | 回到顶部 |
| `openLightbox(src, alt)` | `string, string` | 打开图片灯箱 |

导航统一用 `<a href>`（history 路由由 runtime 接管），不提供 `navigate()`。

### 3.4 组件

| 组件 | props | events | 自取数据 |
| --- | --- | --- | --- |
| `<blog-post-card>` | `post`, `variant?` | — | 否 |
| `<blog-post-list>` | `posts`, `layout?` | — | 否 |
| `<blog-markdown>` | `source` | — | 否 |
| `<blog-toc>` | `selector?` | — | 否（读 DOM） |
| `<blog-pager>` | `pager` | `go` | 否 |
| `<blog-tag-chip>` | `tag` | — | 否 |
| `<blog-tag-cloud>` | — | — | **按需**（优先复用路由快照） |
| `<blog-archives>` | — | — | **按需**（优先复用路由快照） |
| `<blog-search-box>` | `value?` | `search` | 否 |
| `<blog-theme-toggle>` | — | — | 否 |
| `<blog-image>` | `src`, `alt`, `ratio?` | — | 否（含灯箱） |
| `<blog-back-to-top>` | — | — | 否 |
| `<blog-reading-progress>` | — | — | 否 |
| `<blog-state>` | `state`, `message?` | — | 否（四态占位） |

规则：

- **可选数据默认不预取**：标签 / 归档页由路由守卫取核心数据，组件直接复用；非数据页放 `<blog-tag-cloud>` / `<blog-archives>` 时组件按需请求，不放就零成本。
- 新增组件 = 纯增量，不升契约；删除 / 改 props = 破坏性变更。
- 组件内部样式由 runtime.css 提供，主题可覆盖但不得破坏其行为。

### 3.5 注入的站点配置键（KV 契约）

| 键 | 类型 | 默认值 | 公开 | 说明 |
| --- | --- | --- | --- | --- |
| `site.name` | STRING | `Simple Notes` | 是 | 站点名 |
| `site.tagline` | STRING | `随手记，也认真写博客` | 是 | 副标题 |
| `site.description` | STRING | `''` | 是 | 站点描述 |
| `site.baseUrl` | STRING | `''` | 是 | 绝对地址（分享 / RSS 用） |
| `site.logoUrl` | STRING | `null` | 是 | 文字 logo 时留空 |
| `site.faviconUrl` | STRING | `null` | 是 | 留空用内置 |
| `site.icp` | STRING | `null` | 是 | 备案号 |
| `site.policeRecord` | STRING | `null` | 是 | 公安备案 |
| `author.name` | STRING | `''` | 是 | 作者展示名 |
| `author.avatarUrl` | STRING | `null` | 是 | 头像 |
| `author.bio` | STRING | `''` | 是 | 一句话简介 |
| `social.links` | JSON | `[]` | 是 | `[{ type, label, url }]` |
| `nav.items` | JSON | 内置五项 | 是 | `[{ key, label, visible, order }]` |
| `home.pageSize` | NUMBER | `10` | 是 | 1–50 |
| `footer.text` | STRING | `''` | 是 | 页脚文案 |
| `theme.mode` | ENUM | `system` | 是 | `system / light / dark` |
| `theme.id` | STRING | `''` | 是 | 当前生效主题；空 = 内置默认主题。**系统托管**：只由主题管理写入 |
| `theme.overrides` | JSON | `{}` | 是 | 仅允许 §3.6 的 CSS 变量名 |

规则：

- 值为空 = 使用默认值；**空表必须等价于当前线上表现**。
- 未注册的键：读取忽略、写入拒绝（400）。
- **系统托管键**：`theme.id` 不在管理端站点设置中展示 / 提交；写入只允许主题管理的「应用 / 回滚」（见 §7.1）。绕过该接口直接提交 `theme.id` 返回 400。
- 仅 `公开 = 是` 的键进入 `__BLOG_BOOT__`；未来私有键不得透出。
- 键名属于契约，改名 = 破坏性变更。

### 3.6 CSS 变量与 DOM 钩子

主题通过以下变量做视觉适配；`theme.overrides` 只允许覆盖这张表里的变量。

| 变量 | 用途 |
| --- | --- |
| `--blog-accent` | 主色 |
| `--blog-accent-contrast` | 主色上的文字色 |
| `--blog-bg` | 页面背景 |
| `--blog-surface` | 卡片 / 面板背景 |
| `--blog-text` | 正文 |
| `--blog-text-muted` | 次要文字（对比度须 ≥ 4.5:1） |
| `--blog-border` | 分隔线 |
| `--blog-radius` | 圆角基准 |
| `--blog-font-sans` / `--blog-font-mono` | 字体族 |
| `--blog-prose-size` / `--blog-prose-line-height` | 正文字号 / 行高 |
| `--blog-content-width` | 阅读区最大宽度 |
| `--blog-gap` | 间距基准 |
| `--blog-code-bg` | 代码块背景 |

DOM 钩子（只增不改）：

```text
[data-blog-shell]                     根容器
[data-blog-page="home|post|tags|..."] 当前页面
[data-color-mode="light|dark"]        生效模式
[data-state="loading|empty|error|ready"]
[data-blog-header] [data-blog-nav] [data-blog-footer]
[data-blog-post-card] [data-pinned] [data-has-cover]
[data-blog-prose]                     Markdown 输出容器
[data-blog-boot]                      首屏轻量加载层（壳内联，主题可 CSS 隐藏）
[data-blog-progress]                  站内导航顶部进度条（壳内联）
html[data-blog-ready="1"]             首屏数据已就绪，加载层淡出
html[data-blog-navigating="1"]        路由取数中，顶部进度条可见
```

首屏加载层由后端壳在主题 HTML 之前输出，基础样式内联在 `<head>`：`runtime.css` 随 JS 到达，赶不上首帧。它只取 `--blog-bg` 与 `--blog-text-muted` 两个变量，默认显示；主题若要纯空白首帧，可在 `theme.css` 写 `[data-blog-boot]{display:none}`。启动失败时 runtime 会以内联 `!important` 强制显示错误文案，主题的隐藏规则不会吞掉错误。

---

## 4. 主题模型

### 4.1 主题 = HTML + CSS，零 JS

- 主题必须提供 `<div id="app">` 根节点；模板由 runtime 编译。
- **禁止** `<script>`、`v-html`、`window` / `fetch` / `eval` / `localStorage` 等表达式。
- 允许：`v-if` / `v-for` / `v-show` / `:class` / `:style` / `@click`（只能调用 §3.3 的动作）、组件标签。
- 主题可以只实现部分页面，缺失页面回落内置默认主题。

### 4.2 文件模型

```text
theme.html    整页 body 骨架（含 header / main / footer）
theme.css     全部样式（含暗色与响应式）
```

不引入第三个 manifest 文件；元信息（名称、契约版本）由管理端表单录入并存表。

内置默认主题的唯一源是 `frontend/packages/theme-starter/`，随 runtime 构建进入 bundle，同时作为管理端「新建主题」的起点：

```text
frontend/packages/theme-starter/{theme.html,theme.css,src/index.ts}
backend/src/main/resources/blog/default-theme/{theme.html,theme.css}   ← pnpm sync:theme 同步的兜底副本
```

后端拼壳需要自包含的兜底资源（不能依赖前端构建或 CDN 在线），因此保留一份副本；`pnpm check:theme` 校验两份内容哈希一致，禁止手工只改一边。

> **升级注意**：主题版本不可变。修改 `theme-starter`（例如新增 Lute `.callout` / KaTeX / 任务列表样式）**不会自动更新已存库的激活主题**；部署后需在管理端新建主题版本并人工应用，否则博客仍使用旧 CSS。

### 4.3 能力边界

| 能做 | 不能做 |
| --- | --- |
| 任意 DOM 结构与区块顺序 | 新增页面类型 / 路由 |
| 每页独立版式 | 新增领域数据（评论、阅读量） |
| 导航形态（顶栏 / 侧栏 / 抽屉） | 新增交互行为（需 runtime 加组件） |
| 条件渲染、循环、分组 | 修改 Markdown 方言 |
| 明暗双套配色 | 引入第三方脚本 |
| 响应式任意断点 | 修改 URL 结构 |

### 4.4 数据选择

| 类型 | 策略 |
| --- | --- |
| 核心（`state` / `posts` / `pager` / `post` / `prevPost` / `nextPost`） | runtime 必给，一次请求 |
| 可选（`tags` / `archives`） | 标签 / 归档页由路由守卫预取；其他页面由组件按需自取，`v-if` 同时控制渲染与请求 |
| 缺省页面 | 回落内置默认主题 |

模板中禁止复杂逻辑；runtime 提供 `take(list, n)`、`pinned(list)` 等小工具。

### 4.5 路由

| 路径 | 页面 | 数据 |
| --- | --- | --- |
| `/` | 首页 | `posts` + `pager` |
| `/posts/:slug` | 文章详情 | `post` + `prevPost` / `nextPost` |
| `/tags` | 标签 | `tags` |
| `/tags/:id` | 标签文章 | `tags` + `tag` + `posts` + `pager` |
| `/archives` | 归档 | `archives` + `posts` + `pager` |
| `/search` | 搜索 | `keyword` + `posts` + `pager` |
| `/about`、`/p/:slug` | 独立页面 | `page` |
| 其他 | 404 | — |

后端对以上路径统一 fallback 到同一 HTML 骨架；URL 结构对外是契约，不随主题变化。

**fallback 规则（v1.5）**：后端不再逐个登记页面路径。除保留前缀（`/api`、`/blog`、`/v3`、`/error`、
`/favicon.svg`）外，任意 GET 都返回同一壳（`BlogPageController.SHELL_PATH`），因此**新增前端路由无需改后端**。
未知页面返回 `200 + 壳`，由 runtime 渲染 not-found；真 404 留待发布时预渲染落地后再引入（SEO 仍为非目标）。

保留前缀的原因：`/api` 的未知路径必须是 401 / 404 JSON（不能退化成 HTML）；`/blog` 是 jar 内静态资源，
catch-all 会抢占它（`RequestMappingHandlerMapping` 先于资源 handler）；`/v3`、`/error`、`/favicon.svg`
分别是契约、错误分发与根静态文件。

---

## 5. 页面组装与安全

### 5.1 请求链路

```text
GET /posts/hello
  → 匹配到公开路由 → 读取 site 配置 + ACTIVE 主题版本
  → 组装 HTML：
       <script>window.__BLOG_BOOT__ = {site, themeMode, adminOrigin, contract, theme:{id,versionNo,contract}}</script>
       <style>[v-cloak]{display:none}</style>
       <style id="blog-boot-style">/* 首屏加载层，内联 */</style>
       <style id="blog-theme">/* 主题 CSS，内联 */</style>
       <body data-theme-id=…>
         [data-blog-boot]      /* 轻量加载层 */
         [data-blog-progress]  /* 顶部进度条 */
         /* 主题 HTML，原样注入 */
       <script type="module" src="/blog/runtime.js"></script>
  → 返回整页（ETag = 主题版本 + 配置版本 + 壳版本；s-maxage=60）
```

- 主题 CSS 内联：避免二次请求与首帧闪烁；体积上限 256KB。
- **首屏加载层**：壳在主题 HTML 之前输出 `[data-blog-boot]`，基础样式内联且排在主题 CSS 之前（主题可覆盖 / 隐藏）。runtime 在首次路由数据取好、Markdown 可渲染后置 `html[data-blog-ready="1"]`，加载层淡出；没有最短展示时间，也不做延迟阈值。
- **路由级取数**：`router.beforeResolve` 先取目标页数据，导航确认后 `afterEach` 一次性写入 store。首屏 `router.isReady()` 因而包含首次取数，app 挂载时数据已就绪，不会出现 `.bc-skeleton`。站内跳转期间保留旧页面，只显示 `[data-blog-progress]` 顶部进度条；快速连点会中止上一笔请求。
- **首屏挂载顺序**：`router.isReady()` → mount → 等 Markdown 首帧（文章 / 独立页）→ `removeCloak()` → `data-blog-ready=1`。
- runtime 由 Vite 构建，Maven 在 `prepare-package` 把 `apps/blog/dist` 拷入 jar 的 `static/blog/`；入口名固定，chunk / 静态资源带内容哈希。
- `runtime.js` 由后端以 `no-cache` 提供；`/blog/chunks/**`、`/blog/assets/**` 长缓存 + `immutable`（见 `BlogAssetCacheFilter`）。
- 依赖自托管：Vue（含模板编译器）打进构建产物；Lute / hljs / KaTeX / Mermaid 作为 `/blog/vditor/**` 静态资源按需加载，**不使用公共 CDN**（可用性、CSP、供应链）。
- runtime 基础样式（`runtime.css`）由构建产物内联注入；**首屏加载层样式不能放在 runtime.css**，必须由壳内联，否则 JS 到达前无样式。
- 壳与路由无关：不注入 `path`，runtime 自己读 `location`，因此同一份壳可被所有路由复用。
- **路由 fallback**：`BlogPageController` 只保留 `SHELL_PATH` 一条 catch-all（+ `/`），不再逐个登记页面；页面路由的唯一来源是 runtime 的路由表（`frontend/apps/blog/src/runtime.ts`）。
- **单启动路径**：runtime 只从 `window.__BLOG_BOOT__` 启动；不存在客户端 bootstrap 分支。`GET /api/blog/bootstrap` 保留为调试 / agent / 未来桌面端的读取入口。

### 5.2 安全边界

| 项 | 措施 |
| --- | --- |
| 分域 | blog 与 admin 不同源；blog 不持有 admin 凭证 |
| 匿名面 | 非 API 的 GET 一律公开（壳 fallback + jar 内静态资源），`/api/**` 要求会话。因此新增业务控制器**必须**挂在 `/api/` 下：`startup/ApiPathValidator` 在启动期扫描 handler，漏写前缀直接终止启动 |
| CSP | `default-src 'self'; script-src 'self' 'unsafe-eval'`；主题 CSS 需 `style-src 'unsafe-inline'`；KaTeX 字体需 `font-src 'self' data:`；同源部署下无需放行资产域（开发期 `BLOG_ASSET_BASE` 指向 Vite 时自动把该 origin 加入 script/style/font/connect） |
| `unsafe-eval` | Vue 运行时模板编译必需；靠零主题 JS + 校验 + 分域兜底 |
| 模板校验 | 保存时后端校验；**加载时 runtime 再校验一次**（库内旧数据 / 被篡改数据不得直接进编译器）；见 §8 |
| 预览隔离 | 草稿仅通过短期签名 token 可见（30 分钟，HMAC）；页面 `no-store` + `no-referrer`，bootstrap 同样 `no-store` |
| 嵌入控制 | `frame-ancestors 'self' https://admin.example.com`（由后端壳输出） |
| 缓存 | 非预览壳 `s-maxage=60` + `stale-while-revalidate`；文章发布不失效壳，主题激活 / 配置保存 / 发版才变 |

### 5.3 构建与部署

- **单产物**：`pnpm build:blog` → Maven `prepare-package` 拷贝 `../frontend/apps/blog/dist` 到 jar 的 `static/blog/` → `mvn package`。部署与回滚都以 jar 为单位。
- 构建顺序是唯一新增依赖：后端 `mvn test` / `compile` 不需要 dist；`mvn package` 必须在 `pnpm build:blog` 之后。缺失产物时 Maven 直接失败，不会打出空壳 jar。
- 开发期：`mvn spring-boot:run` 默认启用 `dev` profile，`asset-base` 自动指向 Vite（5174），页面仍在 8080；生产 jar 不启用该 profile，固定 `/blog`。
- 推荐同源；若在整站前面挂 CDN，按整站回源缓存即可，不需要单独的资产域或 CORS。
- admin 为独立前端域，调同一 API（跨域，需 CORS 白名单）。
- `VITE_BLOG_URL`（admin）= blog 公开地址（预览 iframe 指向后端壳）；后端配置 admin 域名用于 `frame-ancestors` 与 postMessage 白名单。

---

## 6. 数据模型

### 6.1 主题

```sql
create table site_theme (
    id                varchar(36) not null primary key,
    name              varchar(64) not null,
    active_version_id varchar(36),
    -- 审计七件套
);

create table site_theme_version (
    id            varchar(36) not null primary key,
    theme_id      varchar(36) not null references site_theme (id),
    version_no    int         not null,
    format        smallint    not null default 1,
    html          text        not null,
    css           text        not null,
    contract      int         not null,
    create_by     varchar(36),
    create_time   timestamptz not null,
    constraint uk_theme_version unique (theme_id, version_no)
);
```

- `format` 标识主题包形态：`1` = `html + css`（v1）。后续若引入 manifest / 多文件，新增 `format = 2`，存量行不动，渲染层按 `format` 分发（§3.1 的 L2）。

- `site_theme_version` **不可变**，只 INSERT（与 `note_revision` 同构）；例外需记入 `backend/AGENTS.md`。
- 当前生效主题 = `site_setting` 的 `theme.id` + 该主题的 `active_version_id`。
- 回滚 = 移动 `active_version_id`。
- 内置默认主题不存库；表为空时使用它。
- `theme.id` 与 `active_version_id` 必须一起变更，唯一写入点是主题管理的 `activate`；`PUT /api/admin/site/settings` 不接受 `theme.id`（400），站点设置页只读展示当前主题名称。

### 6.2 站点配置（KV）

```sql
create table site_setting (
    setting_key   varchar(100) not null primary key,
    setting_value text,
    create_time   timestamptz not null,
    update_time   timestamptz,
    create_by     varchar(36),
    update_by     varchar(36)
);
```

- 无软删除、无 `delete_flag`：物理 upsert，删除即删行（审计例外，需记录）。
- 类型、分组、默认值、校验、是否公开全部在代码侧注册表（`SiteSettingDefinition`），不入库。
- 复杂值以 JSON 字符串存储，并带形态版本 `{"v":1,...}`；`theme.overrides` 是扁平 map（键受 §3.6 白名单约束），不额外包版本。
- 批量保存 `PUT /api/admin/site/settings`，body 含 `version`；冲突 409。`version` 取最近一次写入时间（毫秒），空表为 0。

### 6.3 独立页面

```sql
create table site_page (
    id          varchar(36) not null primary key,
    slug        varchar(64) not null,
    title       varchar(200) not null,
    content     text not null,
    visible     smallint not null default 1,
    sort        int not null default 0,
    -- 审计七件套
);
create unique index uk_site_page_slug on site_page (lower(slug)) where delete_flag = 0;
```

- **不做发布快照**：保存即线上（站点家具，与文章刻意不同）。
- `about` 为预置 slug，`/about` 直接映射；新页面走 `/p/:slug`。
- 内容复用同一 Markdown 方言，用 `<blog-markdown>` 渲染。

### 6.4 迁移与权限

| 迁移 | 内容 |
| --- | --- |
| `V6__init_site_theme.sql` | `site_theme`、`site_theme_version` |
| `V7__init_site_setting.sql` | `site_setting` |
| `V8__init_site_page.sql` | `site_page` |
| `V9__add_site_permissions.sql` | 菜单与动作节点（只 INSERT） |

版本号以实际实现顺序为准；已执行脚本不可修改。

---

## 7. 管理端

### 7.1 主题管理

- 列表：主题名 / 当前版本 / 契约版本 / 是否生效 / 更新时间。
- 编辑：`theme.html` + `theme.css` 双编辑区（或从 agent 结果填入）。
- 流程：保存草稿版本 → 校验 → 预览 → 应用 / 回滚。
- **切换主题的唯一入口**：选中目标主题 → 选版本 → 「应用」。应用会同时写入 `site_theme.active_version_id` 与 `site_setting.theme.id`，两者不可分开修改。
- 非线上主题保留自己的 `activeVersionId`（便于切回），但只有 `isActiveSiteTheme` 为真时才显示「已应用」并禁用按钮。
- 权限：`admin:theme`（菜单 + 读 + 预览）、`admin:theme:write`、`admin:theme:activate`。

### 7.2 预览协议

```text
iframe src = {BLOG_URL}/?previewTheme={versionId}&token={signedToken}

token = HMAC-SHA256(versionId + "." + exp)，TTL 30 分钟，由后端签发
```

`postMessage` 消息（双向校验 `event.origin`）：

| 方向 | type | 载荷 |
| --- | --- | --- |
| admin → blog | `blog-preview:validate` | `{ id, html?, css? }`（省略则校验当前加载版本） |
| blog → admin | `blog-preview:validated` | `{ id, ok, errors: [{ line, message }] }` |
| admin → blog | `blog-preview:color-mode` | `{ mode }` |
| admin → blog | `blog-preview:scroll-to` | `{ selector }` |
| blog → admin | `blog-preview:ready` | `{}` |
| blog → admin | `blog-preview:error` | `{ message }` |

- 校验逻辑只在 runtime 一处，admin 不引入 Vue 编译器。
- 视口切换由 admin 调整 iframe 宽度（320 / 375 / 768 / 1024）。
- 空站点预览支持 `&fixtures=1` 注入示例数据。

### 7.3 站点设置

分组表单：品牌 / 作者 / 社交 / 导航 / 首页 / 页脚 / 主题。保存前做注册表校验，订阅式即时预览（复用主题预览 iframe）。

「主题」分组中的**当前主题只读**：展示主题名称与生效版本号（无 `admin:theme` 权限时显示"由主题管理维护"），并提供跳转到主题管理的入口；切换 / 回滚只在主题管理执行。`theme.id` 不属于可提交配置项。

### 7.4 agent 集成（P2）

| 能力 | 权限 |
| --- | --- |
| 读契约、读当前主题 | ✅ |
| 写草稿版本 | ✅ |
| 跑校验、发起预览 | ✅ |
| 改站点配置白名单键 | ✅ |
| 激活 / 回滚 | ⚠️ 只能提交建议，人点确认 |
| 用户 / 角色 / 发布接口 | ❌ |

上下文必须提供：契约 JSON、starter theme（内置默认主题）、验收清单、校验错误、契约版本号。

安全：文章正文视为不可信输入，不进入主题生成上下文；若必须提供，需显式标注为不可信。

---

## 8. 校验规则

| 类别 | 规则 |
| --- | --- |
| 结构 | 必须含 `#app`；禁 `<script>`；禁 `v-html` |
| 契约 | 模板引用的数据键 / 动作 / 组件必须在白名单内 |
| 危险模式 | 禁 `window` / `document` / `fetch` / `eval` / `localStorage` / `import(` |
| 外部资源 | 禁外域 `url()` / `@import`；图片仅 `/api/media/**` 与 `data:` |
| 体积 | HTML ≤ 64KB；CSS ≤ 256KB |
| 版本 | `runtime.contract - theme.contract ∈ [0, 1]` |
| CSS | `theme.overrides` 的键必须在 §3.6 白名单内，值须为合法颜色 / 长度 |

校验失败一律拒绝保存，返回行级错误。

---

## 9. API 契约

公开（匿名）：

| 方法 / 路径 | 说明 |
| --- | --- |
| `GET /`、`/posts/**`、`/tags/**`、`/archives`、`/search`、`/about`、`/p/**` | 返回组装后的壳（含 fallback；ETag + `s-maxage`，预览 `no-store`） |
| `GET` 其他任意非保留路径（如新增前端路由） | 同样返回壳（`200 + 壳`，runtime 渲染 not-found）；保留前缀见 §4.5 |
| `GET /blog/runtime.js`、`/blog/chunks/*`、`/blog/assets/*` | 后端从 jar 内 `static/blog/` 同源提供；入口 `no-cache`，哈希资源长缓存 |
| `GET /api/blog/bootstrap` | 保留为调试 / agent / 未来桌面端的结构化读取入口：`{ site, theme, runtimeContract, adminOrigin, preview, settingVersion }`；**不再参与首屏启动**；支持 `previewTheme` + `token`，`no-store` |
| `GET /api/blog/posts` | 已有；`page` / `size` / `tagId` / `year` / `month` |
| `GET /api/blog/posts/{slug}` | 已有 |
| `GET /api/blog/tags`、`/api/blog/archives`、`/api/blog/search` | 已有 |
| `GET /api/blog/pages/{slug}` | 新增：独立页面 |
| `GET /api/blog/site` | 新增：站点公开配置（供预览 / 未来的桌面端复用） |
| `GET /api/media/{id}` | 已有 |

管理端：

| 方法 / 路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /api/admin/themes` | `admin:theme` | 列表 |
| `GET /api/admin/themes/{id}/versions/{v}` | `admin:theme` | 读取版本源码 |
| `POST /api/admin/themes/{id}/versions` | `admin:theme:write` | 新增草稿版本 |
| `POST /api/admin/themes/{id}/versions/{v}/preview-token` | `admin:theme` | 签发预览 token |
| `POST /api/admin/themes/{id}/versions/{v}/activate` | `admin:theme:activate` | 应用 / 回滚 |
| `GET /api/admin/site/settings` | `admin:site` | 读取（含默认值与元信息） |
| `PUT /api/admin/site/settings` | `admin:site:update` | 批量保存 |
| `GET /api/admin/site/pages`、`POST`、`PUT`、`DELETE` | `admin:site` / `admin:site:update` | 独立页面 CRUD |

### 9.1 权限码与菜单

| code | 类型 | 名称 | 父节点 | sort |
| --- | --- | --- | --- | --- |
| `admin:site` | MENU | 站点设置 | `entry:admin` | 70 |
| `admin:site:update` | ACTION | 修改站点设置 | `admin:site` | 1 |
| `admin:theme` | MENU | 主题管理 | `entry:admin` | 75 |
| `admin:theme:write` | ACTION | 编辑主题 | `admin:theme` | 1 |
| `admin:theme:activate` | ACTION | 应用 / 回滚主题 | `admin:theme` | 2 |

页面注册表新增：`admin:site` → `/site`、`admin:theme` → `/themes`。`Perms` 常量同步新增，启动对账必须通过。

---

## 10. 质量验收

每套主题（含内置默认）必须通过：

| 项 | 标准 |
| --- | --- |
| 壳首帧 | 生产路径首屏即主题壳（无整页骨架）；数据就绪后加载层淡出，**不出现 `.bc-skeleton` 闪烁**；暗色用户首帧不闪白 |
| 导航 | 站内跳转取数期间保留旧页面 + 顶部进度条；到达后一次性切换，无空态闪烁 |
| 降级 | runtime 加载失败时仍呈现主题壳 + `<noscript>` 提示；脚本超时未启动时加载层给出错误；无横向滚动 |
| 壳缓存 | 主题激活 / 站点配置保存后 ≤ 60s（或 purge 后立即）刷新可见 |
| 单启动路径 | 深链直开、站内跳转、预览、暗色都走同一套后端壳启动；不存在客户端 bootstrap 分支 |
| 打包 | `pnpm build:blog` → `mvn package` 后 jar 内存在 `static/blog/runtime.js`；缺失产物时构建失败 |
| 窄屏 | 320 / 375 / 414 无横向滚动；ready 态可见首篇文章标题 |
| 触控 | 可点元素 ≥ 44×44px |
| 四态 | loading / empty / error / ready 均有明确呈现 |
| 暗色 | 明暗双套配色；图片不过曝；代码块主题成对 |
| 对比度 | 正文与次要信息 ≥ 4.5:1 |
| 字体 | 中文正文 16–17px、行高 ≥ 1.7、阅读区 ≤ 72ch |
| 动效 | 统一 150–200ms；`prefers-reduced-motion` 降级 |
| 安全区 | `env(safe-area-inset-*)`；`viewport-fit=cover` |
| Markdown | 通过 `docs/markdown-dialect-fixture.md` 全量渲染比对 |
| 契约 | 校验零错误；版本在兼容窗口内 |

店铺式回归：每套主题 × 每页 × 三档视口 × 四态。

---

## 11. 实施计划

**P1（不含 agent，约 3–3.5 周）**

1. runtime：Vue / 路由 / API 客户端 / Lute 方言（`@simple-notes/markdown`）/ 组件库 / 四态 / 契约版本
2. 后端：主题存储 + 版本 + 组装壳 + 路由 fallback + 配置注册表 + 独立页面
3. 管理端：主题编辑 / 校验 / 预览 / 应用 / 回滚；站点设置；独立页面
4. 内置默认主题（移动端 + 暗色 + 四态）

**P1.5（方案 C，2026-10-25 落地）**

1. runtime 改为 Vite 构建产物（TS、共享 markdown 方言、入口固定 `runtime.js`）
2. Maven `prepare-package` 把 `apps/blog/dist` 拷入 jar；后端同源提供 `/blog/**`，单产物发布
3. 壳只保留一条启动路径（`window.__BLOG_BOOT__`）；runtime 不再有 bootstrap 启动分支
4. 默认主题收敛到 `packages/theme-starter` + 后端兜底副本同步与校验

**P2（agent，约 2–4 周）**

1. 会话与工具调用（读契约 / 写草稿 / 校验 / 预览）
2. diff 展示与人工确认
3. 上下文工程（starter theme + 验收清单 + 错误回灌）

**P3（可选）**

- 无头浏览器三档视口截图回喂；
- 图片派生、RSS / sitemap、文章级 SEO。

---

## 12. 风险与开放问题

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| 模板静默失败 | 引用不存在的键不会报错，页面只是变空 | 保存时静态校验 + 加载时再校验 + 预览必看 + 四态检查 |
| `unsafe-eval` | 模板编译依赖 `new Function` | 分域 + 零主题 JS + CSP + 校验 |
| 契约漂移 | 主题是数据，没有编译期 | 三张冻结表 + 版本门禁 + 双端校验 + 回归 |
| 非 API 接口误公开 | 壳 fallback 要求"非 API 的 GET 一律公开"，漏写 `/api` 前缀的读接口会被匿名访问 | `startup/ApiPathValidator` 启动期校验：本项目 handler 必须在 `/api/` 下（壳控制器除外），否则启动失败 |
| 主题质量参差 | agent 产出不稳定 | starter theme + 验收清单 + 截图回归（P3） |
| 构建顺序耦合 | `mvn package` 需要先 `pnpm build:blog` | 缺失产物直接失败；CI 用 artifact 传递；`mvn test` 不依赖 dist |
| 前端发布随后端 | runtime 改动必须重打 jar | 当前规模可接受；若独立发布成为硬需求，再外置为方案 A/B |
| 默认主题副本漂移 | packages 与后端兜底各一份 | `pnpm sync:theme` + `pnpm check:theme` 哈希校验 |
| 壳缓存过期 | 主题激活后 CDN 仍给旧壳 | 先 `s-maxage=60`；需要即时生效时接 purge |
| 首屏多一次请求 | 数据由 runtime 取 | 路由守卫在导航确认前取数，壳内加载层覆盖等待期；文章页的 Lute 与正文请求并行；列表仍只需一次请求 |
| 加载层被主题覆盖 / 隐藏 | 主题 CSS 排在加载层样式之后 | 作为 §3.6 稳定钩子文档化；启动错误用内联 `!important` 强制显示 |

开放问题：

1. 文章级 SEO / OG：明确不做，后续若做需重新评估 SPA 抓取问题。
2. 图片派生：本期不做，移动端图片靠 CSS + 上传规范。
3. 评论、阅读量：领域数据，届时由 runtime 增字段（升契约）。
4. 主题数量膨胀后的回归成本：待主题数 > 3 时再引入自动化截图。
5. ~~blog 运行时是否长期依赖 `packages/shared` 的等价实现~~ → 已解决（2026-10-25）：runtime 改为构建产物，直接复用 `packages/shared` 的方言配置与 hljs 实例。

---

## 13. 对既有文档的影响（实现时同步修订）

| 文档 | 需要修订的内容 |
| --- | --- |
| `docs/tech-stack.md` | 博客前台 Markdown 渲染由 md-editor-v3 `MdPreview` 改为运行时内置 `markdown-it`（方言一致）；2026-10-25 起进一步改为 **Lute**（与 admin 同引擎） |
| `docs/markdown-dialect.md` | 明确方言的唯一浏览器实现位于 blog runtime；admin 使用 Vditor/Lute（方言 v2） |
| `frontend/AGENTS.md` | `apps/blog` 为 Vite 构建的 runtime（TS，无 SFC 依赖）；产物在 Maven 打包时进入后端 jar；共享方言与默认主题位置 |
| `docs/content-model.md` | §9.2 前台信息架构改为「后端组装壳 + 运行时」；§10 补充 Markdown 渲染归属 |
| `backend/AGENTS.md` | 记录 `site_theme_version` 不可变、`site_setting` 物理 upsert 两项审计例外；主题以 API / 壳下发，后端不执行主题模板；jar 内 runtime 的打包与缓存约定 |

---

## 14. 变更记录

| 日期 | 版本 | 变更 |
| --- | --- | --- |
| 2026-10-24 | v1 草案 | 初稿：运行时/主题职责边界、三张冻结契约表、主题版本与预览协议、KV 配置、独立页面、agent 权限与验收清单 |
| 2026-10-24 | v1.1 实现 | P1 落地：`V6`–`V9` 迁移；`site_theme` / `site_theme_version`（不可变）/ `site_setting`（KV + 注册表）/ `site_page`；`BlogPageController` 组装页面（CSP、预览 token、契约回落）；无构建 runtime（Vue + vue-router + markdown-it + 自托管 hljs）与内置默认主题；管理端「站点设置」「主题管理」（编辑 / 预览 iframe / 应用 / 回滚）；`Perms` +5；单测覆盖预览 token 与配置注册表 |
| 2026-10-25 | v1.2 实现（方案 A） | runtime 改为独立 Vite 构建产物（TS，复用 `packages/shared` 方言与 hljs；入口固定 `runtime.js`），后端不再托管；壳支持 `asset-base`、ETag / `s-maxage`、预览 `no-store` / `no-referrer`；新增 `GET /api/blog/bootstrap` 与运行时双启动路径；默认主题收敛到 `packages/theme-starter`（后端兜底副本由 `pnpm sync:theme` 同步、`check:theme` 校验）；管理端新建主题复用 starter；数据库零迁移 |
| 2026-10-25 | v1.3 实现（方案 C） | 收敛为单产物：Maven `prepare-package` 把 `apps/blog/dist` 拷入 jar 的 `static/blog/`，后端同源提供 `/blog/**`；删去 runtime 的 bootstrap 启动分支（`GET /api/blog/bootstrap` 降级为调试 / agent 接口）；`/blog/runtime.js` `no-cache`、哈希资源长缓存；生产不再需要 `BLOG_ASSET_BASE`；修复 `pnpm gen:api` 在 Windows 下的变量展开；数据库零迁移 |
| 2026-10-25 | v1.4 实现 | runtime 的 Markdown 渲染由 markdown-it 改为 **Lute**（`@simple-notes/markdown` + `method.min.js`，不打包完整编辑器）；与 admin 共用 `vditorMarkdown` 与 DOMPurify；Vditor 资源自托管在 `/blog/vditor`；主题 CSS 适配 `.callout` / KaTeX / 任务列表；方言 v2 |
| 2026-10-26 | v1.5 实现 | **页面路由归 runtime**：`BlogPageController` 的 8 条页面映射收敛为一条 catch-all（`SHELL_PATH`，排除 `/api`、`/blog`、`/v3`、`/error`、`/favicon.svg`）；未知页面返回 `200 + 壳`，由 runtime 渲染 not-found；`SecurityConfig` 改为"`/api/**` 认证 + 非 API 的 GET 公开"，不再登记页面路径；新增 `startup/ApiPathValidator` 启动期校验（本项目 handler 必须在 `/api/` 下，壳控制器除外），补 `BlogShellRouteIntegrationTest`；数据库零迁移 |
| 2026-10-26 | v1.6 实现 | **首屏去骨架**：壳内联 `[data-blog-boot]` 轻量加载层 + `[data-blog-progress]` 顶部进度条（`SHELL_VERSION` → 3）；runtime 改为 `beforeResolve` 取数、`afterEach` 一次性写入，`router.isReady()` 后挂载，首屏不再出现 `.bc-skeleton`；站内导航保留旧页面并中止上一笔请求；文章页 Lute 与正文请求并行、上下篇后台补取；标签 / 归档组件优先复用路由快照，消除重复请求与空态闪烁；主题可用 CSS 隐藏加载层，启动错误强制显示；数据库零迁移 |
| 2026-10-26 | v1.7 实现 | **主题切换入口收敛**：`theme.id` 改为系统托管键，`PUT /api/admin/site/settings` 拒绝写入（400），站点设置只读展示当前主题名称 + 跳转主题管理；修复非线上主题被误标「已应用」导致「应用」按钮禁用（改为 `isActiveSiteTheme && activeVersionId`）；数据库零迁移 |
