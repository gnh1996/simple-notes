# 博客运行时与主题化设计（Blog Runtime & Theme）

> **状态：v1.20 · 2026-11-19（主题管理主从布局与删除 / 默认主题回退：已实现；v1.18 默认主题页脚与内容摘要 ETag、v1.19 归档默认最新月、v1.20 文档联动：契约规范指针化）**
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

## 3. 运行时契约（冻结表）

契约是主题唯一可依赖的接口。**数据形状 / 配置键 / CSS 变量在实现前冻结；组件与动作只增不改。**

> **唯一登记处**：`frontend/apps/blog/src/contract.ts`；能力清单由 `pnpm sync:contract` 生成到 `docs/theme-contract.generated.md`，本文不复述。
> **规范性规则**（表达式面、规则 ID、错误码、fixture、变更协议）见 `docs/theme-contract.md`。
> `theme-contract.json` 是构建产物（`pnpm build:blog` 生成），供管理端、AI 提示词与外部工具消费，**不手工维护**。
> `pnpm check:contract` 在 CI / `typecheck` 中校验「runtime 实际暴露面 == 契约登记」并扫描默认主题（§3.7）。

### 3.1 契约版本

```text
runtime.contract = 1
theme.contract   = N
兼容条件：runtime.contract - theme.contract <= 1
不兼容：不渲染主题，回落内置默认主题，管理端告警
```

- 只增不改；删除或改语义 = 升 major；运行时至少同时支持 `N` 与 `N-1`。
- 管理端保存 / 应用 / 预览时都要做版本校验；runtime 与后端同 jar 发布，契约常量在同一次发版中升级。
- **运行期双端校验**：后端按自己的 `RUNTIME_CONTRACT` 上限兜底，runtime 再按自身支持的契约校验（主题是数据，无法在构建期检查）。

**冻结分级**（避免「什么都不能改」与「什么都能改」两种极端）：

| 级别 | 内容 | 规则 |
| --- | --- | --- |
| **L0 冻结** | 数据形状、配置键名、CSS 变量名、URL 结构、`state` 枚举 | 破坏性变更，必须升 major + 兼容窗口 + 全量回归 |
| **L1 只增** | 组件、动作、新配置键、新数据字段 | 随时可加，不升版本 |
| **L2 自由演进** | 主题包形态、编辑 UI、预览协议、校验实现 | 内部实现，主题无需感知 |

### 3.2 数据形状

注入入口：`window.__BLOG_BOOT__ = { site, themeMode, basePath, contract, preview, theme }`；`route` 由 runtime 读取 `location` 构造；`basePath` 仅预览壳（admin 域 `/__preview`）使用；`colorMode` 由 runtime 结合 localStorage 与系统偏好解析。其余数据按路由取。

| 键 | 作用 |
| --- | --- |
| `site` / `nav` | 站点配置与已过滤导航 |
| `route` / `listSort` | 当前路由；解析后的排序上下文（只读） |
| `state` / `message` | 四态（`loading` / `empty` / `error` / `ready`）与错误文案，主题负责展示 |
| `posts` / `pager` / `post` / `prevPost` / `nextPost` | 列表、分页、详情与相邻文章 |
| `tags` / `tag` / `archives` / `archiveGroups` / `keyword` / `searched` | 标签、归档、搜索 |
| `page` | 独立页面正文（`/about`、`/p/:slug`） |
| `colorMode` | 实际生效的 `light` / `dark`（`system` 已解析） |
| `ui` | **主题自有 UI 状态，唯一可写数据** |

`ui` 规则：键由主题自起、值可序列化、**不持久化**；路由切换时清空（同页 hash 变化不清空）；写入不触发取数；不要放跨页业务状态（持久化用 URL query）。

约束：

- 字段**只增不减**，新增字段必须给默认值；`camelCase`，改名 = 破坏性变更。
- `content` 永远是 Markdown 原文，主题不得自行解析，必须用 `<blog-markdown>`。
- `displayAt = authoredAt ?? firstPublishedAt`；后两者是本站真实发布事件，`authoredAt` 可空。
- 主题做排序切换时写 URL query，不要写 `ui`。
- 首屏与站内跳转都由 runtime 在导航确认前取数，确认后一次性写入：`loading` 不是首屏必经态，**主题不要用骨架屏表达加载**；导航期间旧页面保持可见。

### 3.3 动作与辅助函数

| 动作 | 参数 | 说明 |
| --- | --- | --- |
| `go(page)` | `number` | 列表翻页 |
| `search(keyword)` | `string` | 跳转搜索 |
| `toggleColorMode()` / `setColorMode(mode)` | `'light' \| 'dark' \| 'system'` | 明暗切换 / 显式设置 |
| `scrollToTop()` | — | 回到顶部 |
| `openLightbox(src, alt)` | `string, string` | 图片灯箱 |

只读辅助：`isNavActive(to)`（导航高亮）、`formatDate(value)`（统一日期格式）、`postDate(post)`（当前排序字段对应的日期）。

导航统一用 `<a href>`（history 路由由 runtime 接管），不提供 `navigate()`。

### 3.4 组件

组件清单、props / events / slots / parts / 示例见 `docs/theme-contract.generated.md`（登记处 `contract.ts` 的 `CONTRACT_COMPONENTS`）。规则：

- **一个组件一个 `.vue` 文件**，放 `apps/blog/src/components/`（`blog-post-card` → `post-card.vue`），注册表在 `components/index.ts`；**禁止 `<style>`**，样式一律在 `runtime.css`（否则主题按 `parts` 覆盖会静默失效）。
- `parts` 是公开样式接口、只增不改；主题只能通过这些 class 定制外观，不承诺内部结构。组件输出 class 与登记不一致时 `check:contract` 直接失败。
- 具名 slot 是公开接口：传内容即**整体接管**该组件内部结构，接管后由主题负责可访问性；槽位名 / 作用域变量以登记为准。
- 可选数据默认不预取：标签 / 归档页复用路由快照，非数据页用组件才按需请求。
- 新增组件 = 纯增量，不升契约；删除 / 改 props = 破坏性变更。

### 3.5 注入的站点配置键（KV 契约）

键、类型、默认值、公开范围以 `SiteSettingCatalog` 为唯一来源；此处只记规则：

- 值为空 = 使用默认值；**空表必须等价于当前线上表现**。
- 未注册的键：读取忽略、写入拒绝（400）；键名属于契约，改名 = 破坏性变更。
- **系统托管键 `theme.id`**：只由主题管理的「应用 / 回滚」写入，不进管理端站点设置；绕过接口提交返回 400。
- 只有 `公开 = 是` 的键进入 `__BLOG_BOOT__`；私有键不得透出。

### 3.6 CSS 变量与 DOM 钩子

CSS 变量清单见 `docs/theme-contract.generated.md`；`theme.overrides` 只允许覆盖登记的变量名，runtime 在启动时写到 `document.documentElement.style`（优先级高于主题 CSS）。

DOM 钩子（只增不改）：

```text
[data-blog-shell]                     根容器
[data-blog-page="home|post|tags|..."] 当前页面
[data-color-mode="light|dark"]        生效模式
[data-state="loading|empty|error|ready"]
[data-blog-header] [data-blog-nav] [data-blog-footer]
[data-blog-post-card] [data-pinned] [data-has-cover]
[data-blog-prose]                     Markdown 输出容器
[data-blog-boot]                      首屏轻量加载层（壳内联）
[data-blog-progress]                  站内导航顶部进度条（壳内联）
html[data-blog-ready="1"]             首屏数据就绪，加载层淡出
html[data-blog-navigating="1"]        路由取数中，进度条可见
```

首屏加载层由后端壳在主题 HTML 之前输出、样式内联（主题 CSS 赶不上首帧），只取 `--blog-bg` 与 `--blog-text-muted`。主题若要纯空白首帧可隐藏 `[data-blog-boot]`；启动失败时 runtime 以内联 `!important` 强制显示错误，隐藏规则不会吞掉错误。

### 3.7 契约自检

`frontend/scripts/theme-contract.mjs`（`pnpm check:contract`，已并入 `pnpm typecheck`）保证「文档说的契约」与「runtime 实际暴露的能力」一致：

| 检查 | 失败含义 |
| --- | --- |
| `createTemplateContext()` 键 == `contract.ts` 的 data / helpers / actions | 暴露了没登记的能力，或登记了没实现的键 |
| `components/index.ts` == `CONTRACT_COMPONENTS` | 组件增删没同步契约，或文件未注册 |
| 组件模板输出 class == `parts`；`runtime.css` / 默认主题只用登记 class 与变量 | 改了内部结构却漏更新样式接口（主题会静默失效） |
| 组件文件均为 `.vue` 且不含 `<style>` | 退回模板字符串 / 自带样式，类型检查与 `parts` 覆盖失效 |
| 组件模板 slot + 作用域变量 == `slots` | 槽位改名没同步契约（主题接管处会失效） |
| 能力 fixture（`scripts/fixtures/theme-capabilities.html`）通过 | 扫描器误判合法写法，会挡掉主题作者 |

`--theme <file>` 可校验任意主题 HTML；`--emit` 同时生成 `apps/blog/dist/theme-contract.json` 与后端 `backend/src/main/resources/theme/theme-contract.json`（随仓库提交）。保存主题版本时后端 `ThemeTemplateValidator` 按同一清单再扫一次（含行号），运行时加载前还会 `validateTheme`。

约定：**新增模板键 / 组件 / 变量时，先改 `contract.ts`，再改 `template-context.ts` / `components/index.ts` + 组件文件**；顺序反了会报「已暴露但未登记」。

## 4. 主题模型

### 4.1 主题 = HTML + CSS，零 JS

- 主题必须提供 `<div id="app">` 根节点；模板由 runtime 编译。
- **禁止** `<script>`、`v-html`、`window` / `fetch` / `eval` / `localStorage` 等表达式。
- 允许：`v-if` / `v-for` / `v-show` / `v-model="ui.x"` / `:class` / `:style` / `@click`（只能调用 §3.3 的动作）、组件标签与具名 slot（`#default` 或 `v-slot`）、表达式内的方法调用（`filter` / `slice` / `sort` 等）与箭头函数。精确的标识符来源、表达式全局白名单、`$event` 策略与局部绑定规则见 `docs/theme-contract.md` §5。
- 主题可以只实现部分页面，缺失页面回落内置默认主题。

### 4.2 文件模型

```text
theme.html    整页 body 骨架（含 header / main / footer）
theme.css     全部样式（含暗色与响应式）
```

不引入第三个 manifest 文件；元信息（名称、契约版本）由管理端表单录入并存表。

内置默认主题的唯一源是 `frontend/packages/theme-starter/`，随 runtime 构建进入 bundle，同时作为管理端「新建主题」的起点：

```text
frontend/packages/theme-starter/{theme.html,theme.css,src/index.ts,README.md}
backend/src/main/resources/blog/default-theme/{theme.html,theme.css}   ← pnpm sync:theme 同步的兜底副本
```

后端拼壳需要自包含的兜底资源（不能依赖前端构建或 CDN 在线），因此保留一份副本；`pnpm check:theme` 校验两份内容哈希一致，禁止手工只改一边。

> **升级注意**：主题版本不可变。修改 `theme-starter`（例如新增 Lute `.callout` / KaTeX / 任务列表样式）**不会自动更新已存库的激活主题**；部署后需在管理端新建主题版本并人工应用，否则博客仍使用旧 CSS。

### 4.3 能力边界

| 能做 | 不能做 |
| --- | --- |
| 任意 DOM 结构与区块顺序 | 新增页面类型 / 路由 |
| 每页独立版式 | 新增领域数据（评论、阅读量） |
| 导航形态（顶栏 / 侧栏 / 抽屉） | 新增网络请求 / 新数据源 |
| 主题自有交互状态（抽屉、标签页、页内筛选，走 `ui`） | 修改 Markdown 方言 |
| 条件渲染、循环、分组、页内派生计算 | 引入第三方脚本 |
| 接管组件内部结构（具名 slot） | 修改 URL 结构 |
| 明暗双套配色 | 跨页持久化的界面状态（`ui` 路由切换即清空） |
| 响应式任意断点 | 主题间共享逻辑（没有 JS 模块） |

### 4.4 数据选择

| 类型 | 策略 |
| --- | --- |
| 核心（`state` / `posts` / `pager` / `post` / `prevPost` / `nextPost`） | runtime 必给，一次请求；上下篇由详情接口按排序上下文返回，不再单独拉列表 |
| 排序上下文（`listSort`） | 由 URL query 解析（`pinnedFirst` / `direction` / `sortBy`），列表与详情共用 |
| 可选（`tags` / `archives`） | 标签 / 归档页由路由守卫预取；其他页面由组件按需自取，`v-if` 同时控制渲染与请求 |
| 缺省页面 | 回落内置默认主题 |

模板中禁止复杂逻辑；可用的只读辅助函数见 §3.3（`isNavActive`、`formatDate`），不存在 `take` / `pinned` 之类的模板工具。

### 4.5 路由

| 路径 | 页面 | 数据 |
| --- | --- | --- |
| `/` | 首页 | `posts` + `pager` |
| `/posts/:slug` | 文章详情 | `post` + `prevPost` / `nextPost` |
| `/tags` | 标签 | `tags` |
| `/tags/:id` | 标签文章 | `tags` + `tag` + `posts` + `pager` |
| `/archives` | 归档；未带 `year`/`month` 时自动跳到最新月份 | `archives` + 当前月 `posts` + `pager` |
| `/search` | 搜索 | `keyword` + `posts` + `pager` |
| `/about`、`/p/:slug` | 独立页面 | `page` |
| 其他 | 404 | — |

**排序 query（v1.13）**：首页 / 标签 / 归档 / 搜索都接受 `pinnedFirst`（`true|false`，默认 `true`）、`direction`（`asc|desc`，默认 `desc`）、`sortBy`（`displayAt|firstPublishedAt|lastPublishedAt`，默认 `displayAt`）。

- 归档路由默认 `pinnedFirst=false`；其余列表默认 `true`。
- `direction` 只作用于时间字段；`pinnedFirst=true` 时置顶始终在前，二者不互相翻转。
- 不限制参数组合，但未知字段 / 方向返回 400。
- **归档按月查看 + 月内分页（v1.19 默认最新月）**：带 `year` / `month` 时用 `/api/blog/posts?year=&month=&page=&size=` 取该月文章，`pager` 在月内翻页，左侧计数与列表天然一致。`year` / `month` 过滤跟随 `sortBy` 字段（`displayAt` 用 `coalesce(authoredAt, firstPublishedAt)`），不是固定 `firstPublishedAt`。
- **未带（或非法）`year` / `month` 时自动进入最新月份**：先请求 `/api/blog/archives`，取年月数值最大的一项（不是索引第一项——`direction=asc` 时第一项最旧），以 `router.replace` 守卫重定向到 `?year=…&month=…`，保留 `pinnedFirst` / `direction` / `sortBy`（丢弃 `page`），第二趟导航再取该月文章；首趟索引结果在重定向内复用（`archiveHandoff`），不重复请求。索引为空（全站没有已发布文章）时不重定向，`posts=[]`、`state='empty'`、提示“暂无归档”，且不请求 `/api/blog/posts`。
- 用户显式指定的月份没有文章时不回退到最新月（避免 URL 与内容不一致），`state='empty'`、提示“该月暂无文章”；`go(page)` 会保留 `year` / `month` / 排序 query。
- 进入详情时，runtime 把当前列表的 `pinnedFirst` / `direction` / `sortBy`（以及标签页的 `tagId`、归档筛选的 `year` / `month`、搜索页的 `q`）附加到 `/posts/:slug`；详情默认值与首页相同。
- 搜索页的上下篇携带 `q`，使用与搜索列表相同的标题 / 正文匹配条件，保证停留在搜索结果内。
- 新标签页 / 直接打开详情会丢上下文，按默认值计算上下篇。

后端对以上路径统一 fallback 到同一 HTML 骨架；URL 结构对外是契约，不随主题变化。
`/about` 是 `/p/:slug` 的 alias（共用 `route.name = 'page'`）：vue-router 5 会丢弃同名路由中先注册的一条，因此不允许为两者各建一条同名记录（`pnpm check:contract` 会拦）。

**fallback 规则（v1.5）**：后端不再逐个登记页面路径。除保留前缀（`/api`、`/blog`、`/v3`、`/error`、
`/favicon.svg`）外，任意 GET 都返回同一壳（`BlogPageController.SHELL_PATH`），因此**新增前端路由无需改后端**。
未知页面返回 `200 + 壳`，由 runtime 渲染 not-found；真 404 留待发布时预渲染落地后再引入（SEO 仍为非目标）。

保留前缀的原因：`/api` 的未知路径必须是 401 / 404 JSON（不能退化成 HTML）；`/blog` 是 jar 内静态资源，
catch-all 会抢占它（`RequestMappingHandlerMapping` 先于资源 handler）；`/v3`、`/error`、`/favicon.svg`
分别是契约、错误分发与根静态文件。

## 5. 页面组装与安全

### 5.1 请求链路

```text
GET /posts/hello
  → 匹配到公开路由 → 读取 site 配置 + ACTIVE 主题版本
  → 组装 HTML：
       <script>window.__BLOG_BOOT__ = {site, themeMode, basePath, contract, preview, theme:{id,versionNo,contract}}</script>
       <style>[v-cloak]{display:none}</style>
       <style id="blog-boot-style">/* 首屏加载层，内联 */</style>
       <style id="blog-theme">/* 主题 CSS，内联 */</style>
       <body data-theme-id=…>
         [data-blog-boot]      /* 轻量加载层 */
         [data-blog-progress]  /* 顶部进度条 */
         /* 主题 HTML，原样注入 */
       <script type="module" src="/blog/runtime.js"></script>
  → 返回整页（ETag = 主题版本 + 配置版本 + asset-base + 壳版本；s-maxage=60）
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
| CSP | `default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'`（壳内联启动脚本 + Vue 运行时模板编译）；主题 CSS 需 `style-src 'unsafe-inline'`；KaTeX 字体需 `font-src 'self' data:`；同源部署下无需放行资产域（开发期 `BLOG_ASSET_BASE` 指向 Vite 时自动把该 origin 加入 script/style/font/connect）；独立媒体域时自动把 `MEDIA_PUBLIC_BASE_URL` 的 origin 加入 `img-src` / `media-src` |
| `unsafe-eval` | Vue 运行时模板编译必需；靠零主题 JS + 校验 + 分域兜底 |
| 模板校验 | 保存时后端校验；**加载时 runtime 再校验一次**（库内旧数据 / 被篡改数据不得直接进编译器）；见 §8 |
| 预览隔离 | 草稿仅通过短期签名 token 可见（30 分钟，HMAC）；页面 `no-store` + `no-referrer`，bootstrap 同样 `no-store` |
| 嵌入控制 | `frame-ancestors 'self'` + `X-Frame-Options: SAMEORIGIN`；预览 iframe 与 admin 同源（admin 域 `/__preview`） |
| 缓存 | 非预览壳 `s-maxage=60` + `stale-while-revalidate`；文章发布不失效壳，主题激活 / 配置保存 / 发版才变 |

### 5.3 构建与部署

- **单产物**：`pnpm build:blog` → Maven `prepare-package` 拷贝 `../frontend/apps/blog/dist` 到 jar 的 `static/blog/` → `mvn package`。部署与回滚都以 jar 为单位。
- 构建顺序是唯一新增依赖：后端 `mvn test` / `compile` 不需要 dist；`mvn package` 必须在 `pnpm build:blog` 之后。缺失产物时 Maven 直接失败，不会打出空壳 jar。
- 开发期：`mvn spring-boot:run` 默认启用 `dev` profile，`asset-base` 自动指向 Vite（5174），页面仍在 8080；生产 jar 不启用该 profile，固定 `/blog`。
- 推荐同源；若在整站前面挂 CDN，按整站回源缓存即可，不需要单独的资产域或 CORS。
- admin / blog 各自同源代理 `/api` 到后端；不需要 CORS。admin 域额外代理 `/__preview` 与 `/blog/` 供同源预览 iframe。
- `VITE_BLOG_URL`（admin）= blog 公开地址，仅用于“查看博客”等跳转；主题预览 iframe 走 admin 同源 `/__preview`（admin nginx 代理 `/__preview`、`/blog/` 到 app），不需要跨域 origin 配置。

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
- **删除主题 = 软删除 `site_theme`**（`delete_flag = 1`），`site_theme_version` 保留不动：版本是不可变审计数据，删除后不再通过管理端读取，但不会级联清理；名称因唯一索引带 `delete_flag = 0` 而可复用。
- **线上主题禁止删除**：`site_setting.theme.id` 指向的主题必须先切换（其他主题或内置默认主题）；删除非线上主题不影响线上页面。
- **「使用内置默认主题」是回到默认主题的唯一入口**：清空 `site_setting.theme.id`，不改动各主题自己的 `active_version_id`；权限与 `activate` 相同。该动作同时解除「唯一主题不能删除」的死锁。
- `theme.id` 与 `active_version_id` 必须一起变更，唯一写入点是主题管理的 `activate`（或「使用内置默认主题」的清空动作）；`PUT /api/admin/site/settings` 不接受 `theme.id`（400），站点设置页只读展示当前主题名称。

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

## 7. 管理端

### 7.1 主题管理

- 布局：桌面端「左主题列表 + 右详情」，窄屏列表 / 详情二选一；左侧固定并独立滚动，右侧展示版本历史，避免选中卡片与详情被纵向拉开。
- 列表：内置默认主题作为第一项（`?theme=default`），其余主题按创建时间排列；每项显示名称 / 版本 / 契约 / 线上标记，`⋯` 菜单承载删除（线上主题禁用）。选中项写入 `?theme=<id>`，刷新 / 分享不丢上下文。
- 编辑：`theme.html` + `theme.css` 双编辑区（或从 agent 结果填入）。
- 流程：保存草稿版本 → 校验 → 预览 → 应用 / 回滚。
- **切换主题的唯一入口**：选中目标主题 → 选版本 → 「应用」。应用会同时写入 `site_theme.active_version_id` 与 `site_setting.theme.id`，两者不可分开修改。
- 非线上主题保留自己的 `activeVersionId`（便于切回），但只有 `isActiveSiteTheme` 为真时才显示「已应用」并禁用按钮。
- **删除主题**：只允许删除非线上主题；二次确认展示版本数量，软删除主题身份并保留版本。线上主题给出「先切换」提示，不执行删除；入口挂在主题列表项的 `⋯` 菜单，避免和详情区的选中对象错位。
- **使用内置默认主题**：默认主题作为列表第一项，详情页提供回退按钮，清空 `site_setting.theme.id`；这是删除线上主题前的前置操作，也是主题试验后的恢复路径。
- **版本列表**：桌面端表格只展示版本 / 状态（线上 / 上次应用）/ 创建时间 / 操作，契约列仅在版本与运行时不一致时出现；窄屏改为版本卡片，避免横向滚动表格把操作与时间挤掉。
- **AI 基线**：每个版本行提供「AI 迭代」，以该版本为基线开启新对话；抽屉顶部显示当前基线版本，避免「编辑 v2、AI 却基于 v5」的上下文错位。头部只在有未完成对话时显示「继续 AI 对话」，无版本时显示「AI 生成第一版」，避免同一区域出现两个新建入口。
- 权限：`admin:theme`（菜单 + 读 + 预览）、`admin:theme:write`、`admin:theme:activate`、`admin:theme:delete`。

### 7.2 预览协议

```text
iframe src = /__preview?previewTheme={versionId}&token={signedToken}   # admin 同源

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
- 管理端预览弹窗当前发送 `validate` 与 `color-mode`；`scroll-to` 由 runtime 支持，留给后续工具 / 主题调试使用。
- 视口切换由 admin 调整 iframe 宽度（320 / 375 / 768 / 1024）。
- 空站点预览支持 `&fixtures=1` 注入示例数据（仅在预览壳中生效；管理端预览弹窗有「使用示例数据」开关）。
- 管理端预览弹窗会向 iframe 发送 `blog-preview:validate`，展示运行时返回的契约错误；保存时后端仍会做同一口径的扫描，不能只依赖预览。

### 7.3 站点设置

分组表单：品牌 / 作者（含社交链接）/ 导航 / 首页 / 页脚 / 主题（与 `SiteSettingCatalog` 的 group 一致）。保存前做注册表校验，订阅式即时预览（复用主题预览 iframe）。

「主题」分组中的**当前主题只读**：展示主题名称与生效版本号（无 `admin:theme` 权限时显示"由主题管理维护"），并提供跳转到主题管理的入口；切换 / 回滚只在主题管理执行。`theme.id` 不属于可提交配置项。

### 7.4 agent 集成（P2 → v1 已落地对话式定制，详见 `docs/theme-ai.md`）

| 能力 | 权限 |
| --- | --- |
| 读契约、读当前主题 | ✅ |
| 写草稿版本 | ✅ |
| 跑校验、发起预览 | ✅ |
| AI 对话生成（流式 SSE + 自动修复 + 临时草稿预览） | ✅（`admin:theme:write` 流式生成 / `admin:theme` 预览；无服务端记忆，见 `docs/theme-ai.md`） |
| 改站点配置白名单键 | ✅ |
| 激活 / 回滚 | ⚠️ 只能提交建议，人点确认 |
| 用户 / 角色 / 发布接口 | ❌ |

上下文必须提供：契约 JSON、starter theme（内置默认主题）、验收清单、校验错误、契约版本号。

安全：文章正文视为不可信输入，不进入主题生成上下文；若必须提供，需显式标注为不可信。

## 8. 校验规则

| 类别 | 规则 |
| --- | --- |
| 结构 | 必须含 `#app`；禁 `<script>`；禁 `v-html` |
| 契约 | 模板引用的数据键 / 动作 / 组件必须在白名单内；后端保存时执行与 `theme-contract.mjs` 同口径的保守扫描，运行时加载前再扫描一次 |
| 危险模式 | 禁 `window` / `document` / `fetch` / `eval` / `localStorage` / `import(` |
| 外部资源 | 禁外域 `url()` / `@import`；图片仅 `/api/media/**` 与 `data:` |
| 体积 | HTML ≤ 64KB；CSS ≤ 256KB |
| 版本 | `runtime.contract - theme.contract ∈ [0, 1]` |
| CSS | `theme.overrides` 的键必须在 §3.6 白名单内，值须为安全的颜色 / 长度 / 字体族 token（后端校验；运行时只应用登记变量） |
| 实体归一 | 保存时吃原文、运行时加载时吃 `app.outerHTML`（浏览器序列化把 `&&` 转写为 `&amp;&amp;`、`=>` 转写为 `=&gt;`）；三处扫描（`theme-contract.mjs` / `ThemeTemplateValidator` / runtime `validateTheme`）先按序列化逆操作解码实体再扫描，口径一致，否则合法表达式会被误判为未登记标识符导致回落默认主题 |

校验失败一律拒绝保存，返回带行号的错误；运行时加载到历史 / 被篡改主题时校验失败会回落内置默认主题。

> 规则 ID、稳定错误码、fixture 映射与变更协议见 `docs/theme-contract.md` §6–§9；本节只描述校验策略。
> 主题作者是受信任的管理员 / AI；校验用于防误用与契约维护，不是安全沙箱（见 `docs/theme-contract.md` §11）。

## 9. API 契约

> 端点与 DTO 以 OpenAPI 为准；本节只记壳与主题相关的语义。

- 页面路由（`/`、`/posts/**`、`/tags/**`、`/archives`、`/search`、`/about`、`/p/**`）与其他任意非保留路径都返回同一份壳：`200 + 壳`，由 runtime 渲染或渲染 not-found；ETag + `s-maxage`，预览 `no-store`。保留前缀见 §4.5。
- `/blog/runtime.js`、`/blog/chunks/*`、`/blog/assets/*` 由后端从 jar 内 `static/blog/` 同源提供；入口 `no-cache`，哈希资源长缓存。
- `GET /api/blog/bootstrap` 保留为调试 / agent / 桌面端的结构化入口（`site` / `theme` / `runtimeContract` / `preview` / `settingVersion`），**不参与首屏启动**，`no-store`，支持预览 token。
- 公开读接口一次请求返回当前路由所需数据；排序参数与 `year` / `month` 语义见 `docs/content-model.md` §6.4。

### 9.1 权限码与菜单

| code | 类型 | 名称 | 父节点 | sort |
| --- | --- | --- | --- | --- |
| `admin:site` | MENU | 站点设置 | `entry:admin` | 70 |
| `admin:site:update` | ACTION | 修改站点设置 | `admin:site` | 1 |
| `admin:theme` | MENU | 主题管理 | `entry:admin` | 75 |
| `admin:theme:write` | ACTION | 编辑主题 | `admin:theme` | 1 |
| `admin:theme:activate` | ACTION | 应用 / 回滚主题 | `admin:theme` | 2 |
| `admin:theme:delete` | ACTION | 删除主题 | `admin:theme` | 3 |

页面注册表：`admin:site` → `/site`、`admin:theme` → `/themes`。`Perms` 常量同步，启动对账必须通过。

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

## 11. 风险与边界

| 风险 | 缓解 |
| --- | --- |
| 模板静默失败（引用不存在的键只是变空） | 保存时静态校验 + 加载时再校验 + 预览必看 + 四态检查 |
| 模板编译依赖 `new Function`（CSP `unsafe-eval`） | admin / blog 分域 + 零主题 JS + CSP + 校验 |
| 契约漂移（主题是数据，没有编译期） | 机器源 + 版本门禁 + 双端校验 + `check:contract` + 回归 |
| 非 API 接口误公开（壳 fallback 要求「非 API 的 GET 一律返回壳」） | `startup/ApiPathValidator` 启动期校验：本项目 handler 必须在 `/api/` 下（壳控制器除外），否则启动失败 |
| 构建顺序耦合（`mvn package` 需要先 `pnpm build:blog`） | 缺失产物直接失败；CI 用 artifact 传递；`mvn test` 不依赖 dist |
| 前端发布随后端（runtime 改动要重打 jar） | 当前规模可接受；独立发布成为硬需求时再外置 |
| 默认主题副本漂移 | `pnpm sync:theme` + `pnpm check:theme` 哈希校验 |
| 壳缓存过期（主题激活后 CDN 仍给旧壳） | `s-maxage=60`；需要即时生效时接 purge |
| 加载层被主题覆盖 / 隐藏 | §3.6 稳定钩子；启动错误用内联 `!important` 强制显示 |

未做：文章级 SEO / OG、图片派生、评论与阅读量、主题数膨胀后的自动截图回归（>3 套再引入）。
