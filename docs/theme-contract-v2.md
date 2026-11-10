# 主题契约 v2 设计草案（2A：增量 headless）

> **状态：阶段 1 已实现（2026-11-08）**：契约投影、生成物、后端校验/提示词消费、12 个组件 `root` slot、headless fixture 已落地。**规范性规则已收敛到 `docs/theme-contract.md`**；本文保留 2A 的历史设计记录，规则冲突时以 `docs/theme-contract.md` 为准。
> **关系**：本文是 `docs/blog-runtime-theme.md` §3 的**增量**，不是替代；规范内容并入 `docs/theme-contract.md`。
> **单一来源**：`frontend/apps/blog/src/contract.ts`（配合 `types.ts` / `runtime.ts`）。
> **目标**：让主题可以完全接管 HTML + CSS（headless），同时不破坏已有 v1 主题；让 AI 拿到完整、可执行的契约，而不是手写摘要。
> **非目标**：不新建第二套 manifest；不改变数据语义；不引入新的模板语法；不做“删除默认 DOM”的 2B。

---

## 0. 结论

2A 方案只做四件事：

1. **给展示型 / 数据型组件增加 `root` slot**：主题提供 `root` 时，组件只渲染 slot 内容，默认 DOM 与默认样式不出现；不提供时沿用现有默认结构。
2. **扩展契约投影**：`theme-contract.json` 增加类型字段、slot scope、路由与 URL；由 `contract.ts` + `types.ts` + `runtime.ts` 生成。
3. **后端只读 JSON**：`ThemeTemplateValidator`、`AiThemePrompt`、`SiteSettingCatalog` 不再手写清单。
4. **默认实现保持不变**：`packages/theme-starter` 仍是默认主题与兜底；现有 v1 主题不受影响。

契约版本**不升**：新增 slot 属于 `docs/blog-runtime-theme.md` §3.1 的 L1“只增”。

---

## 1. 现状诊断

### 1.1 一份源头 + 五处镜像

| 位置 | 角色 | 是否自动同步 |
| --- | --- | --- |
| `frontend/apps/blog/src/contract.ts` | **唯一登记处** | 是（源头） |
| `frontend/scripts/theme-contract.mjs` | 校验“暴露面 == 登记面”，`--emit` 生成 JSON | 是 |
| `theme-contract.json` | 构建产物 | **没有被后端消费** |
| `ThemeTemplateValidator` | 保存时校验 | 否，手写镜像 |
| `AiThemePrompt.CONTRACT_RULES` / `ALLOWED_CSS_VARS` | 喂给模型 | 否，手写且更窄 |
| `SiteSettingCatalog` | `theme.overrides` 的 CSS 变量白名单 | 否，手写镜像 |

`docs/blog-runtime-theme.md` §8 已经写明：

> 上下文必须提供：契约 JSON、starter theme、内置默认主题、验收清单、校验错误、契约版本号。

设计意图存在，实现没有接上。

### 1.2 已经存在的真实漂移

这些不是假设，是当前代码里已经发生的不一致：

| 漂移 | 位置 | 后果 |
| --- | --- | --- |
| `--blog-mono` vs `--blog-font-mono` | `AiThemePrompt` 用前者，契约与默认主题用后者 | 模型被要求覆盖一个不存在的变量 |
| `ArchiveGroup[]` 没有对应 TS 类型 | `contract.ts` 引用，`types.ts` 未定义 | 类型投影无法生成 |
| `RouteLocation` 不是实际类型 | `contract.ts` 的 `shape`；实际是 `RouteLocationNormalizedLoaded` | 提示词无法给出准确字段 |
| `ListSort` 已定义却用内联形状 | `contract.ts` 的 `listSort.shape` | 类型投影重复、易漂移 |
| 只有 `blog-post-card` 有 slot | `contract.ts` / 组件 | 其他组件无法 headless |
| 校验器不检查 props / slot / 路由 | `ThemeTemplateValidator` | “通过校验”不等于“能用” |

结论：**不是“契约缺失”，而是“契约没有投影给模型，且后端存在手写镜像”。**

> 阶段 1 已处理：`theme-contract.json` 由 `pnpm sync:contract` 生成并提交到后端资源；`ThemeTemplateValidator` / `AiThemePrompt` / `SiteSettingCatalog` 改为只读该文件；`--blog-mono` / `ArchiveGroup` / `RouteLocation` / `ListSort` 漂移已修。

---

## 2. 设计原则

1. **单一来源**：主题能力只在 `contract.ts` + `types.ts` + `runtime.ts` 定义。
2. **生成物不是源**：`theme-contract.json` 由脚本生成，提交到后端资源，禁止手改。
3. **后端只读生成物**：校验、提示词、配置白名单都从 JSON 派生。
4. **只增不改**：新增 slot / 字段 / 路由不升契约版本；删除或改语义才升 major。
5. **默认实现兜底**：默认 DOM、默认样式、`theme-starter` 都保留，旧主题不需要迁移。
6. **无样式是主题级选择**：主题用 `root` slot 接管后，组件默认 DOM/样式不出现；不是删除组件默认样式。

---

## 3. 契约结构 v2

### 3.1 SlotEntry：增加 scope

```ts
export interface SlotField {
  readonly name: string
  /** 引用 CONTRACT_TYPES 的键；基本类型用 'string' / 'number' / 'boolean' */
  readonly shape: string
  readonly optional?: boolean
  readonly note?: string
}

export interface SlotEntry {
  readonly name: string
  /** 保留：现有 check:contract 与外部消费者使用 */
  readonly provides: readonly string[]
  /** 新增：带类型与语义；provides 必须等于 scope.map(f => f.name) */
  readonly scope: readonly SlotField[]
}
```

`root` 是约定名：主题提供 `#root` 时，组件只渲染 slot 内容，**默认 DOM 与默认样式整体不出现**。其他 slot（如 `default`）只替换内部内容，保留组件外壳。

### 3.2 ComponentEntry：增加必填 props 与示例

```ts
export interface ComponentEntry {
  readonly props: readonly string[]
  /** 新增：props 的子集 */
  readonly requiredProps: readonly string[]
  readonly events: readonly string[]
  readonly selfFetch: boolean
  readonly parts: readonly string[]
  readonly slots: readonly SlotEntry[]
  /** 新增、可选：最小用法示例（本阶段未使用，保留给 cookbook 扩展） */
  readonly example?: string
}
```

`example` 不是第二份契约，它和 `props/slots` 在同一个登记处，且必须通过同一套扫描器。

### 3.3 CONTRACT_TYPES：从 types.ts 生成

`theme-contract.json` 新增：

```json
{
  "types": {
    "PostSummary": {
      "slug": "string",
      "title": "string",
      "summary": "string",
      "coverUrl": "string | null",
      "tags": "TagBrief[]",
      "pinned": "boolean",
      "firstPublishedAt": "string",
      "lastPublishedAt": "string",
      "authoredAt": "string | null",
      "displayAt": "string",
      "readingMinutes": "number"
    }
  }
}
```

生成规则：

* 以 `CONTRACT_DATA[].shape` 为根，递归解析 `types.ts` 的 `interface` / `type`；
* 任何 `shape` 解析不到 → `check:contract` 直接失败；
* 只输出主题可见的类型，不输出 `Boot` / `ThemePayload` 等内部类型。

同时修正当前漂移：

* 新增 `RouteView`（`{ name, path, params, query, hash }`），`route.shape` 改为 `RouteView`；
* 新增 `ArchiveGroup` 类型，`archiveGroups.shape` 使用它；
* `listSort.shape` 改为 `ListSort`；
* `--blog-mono` 统一为 `--blog-font-mono`。

### 3.4 CONTRACT_ROUTES / CONTRACT_URLS

在 `contract.ts` 新增：

```ts
export const CONTRACT_ROUTES = {
  home: { path: '/', query: [] },
  post: { path: '/posts/:slug', query: [] },
  tags: { path: '/tags', query: ['page', 'size', 'sortBy', 'direction', 'pinnedFirst'] },
  'tag-posts': { path: '/tags/:id', query: ['page', 'size', 'sortBy', 'direction', 'pinnedFirst'] },
  archives: { path: '/archives', query: ['year', 'month', 'page', 'size', 'sortBy', 'direction', 'pinnedFirst'] },
  search: { path: '/search', query: ['q', 'page', 'size', 'sortBy', 'direction', 'pinnedFirst'] },
  page: { path: '/p/:slug', alias: ['/about'], query: [] },
  'not-found': { path: '/:pathMatch(.*)*', query: [] },
} as const

export const CONTRACT_URLS = {
  post: '/posts/{slug}',
  tag: '/tags/{id}',
  archives: '/archives?year={year}&month={month}',
  search: '/search?q={q}',
  page: '/p/{slug}',
} as const
```

`check:contract` 校验：

* `CONTRACT_ROUTES` 的 `name` / `path` 与 `runtime.ts` 路由表一致；
* 默认主题与 fixture 的字面量链接符合 `CONTRACT_URLS` 前缀。

### 3.5 Cookbook：给模型的投影

`theme-contract.json` 之外，不新增长期文件。后端启动时从 JSON 生成 cookbook：

```text
<blog-pager>
  props: pager（必填）
  events: go
  slots:
    root: { pager: Pager, go: (page: number) => void, pages: number[] }
  parts: bc-pager
  example: <blog-pager v-slot="{ pager, go }">…</blog-pager>
</blog-pager>
```

cookbook 是 JSON 的纯函数投影，不手工维护；默认主题**不进入** cookbook，避免 starter 偏差。

---

## 4. 组件 slot 设计（2A）

### 4.1 root 与 default 的语义

| slot | 语义 | 默认行为 |
| --- | --- | --- |
| `root` | 整体替换组件输出（含外壳） | 不提供时渲染默认 DOM |
| `default` / 具名 | 替换组件内部内容，保留行为外壳 | 不提供时渲染默认内容 |

`root` 是 2A 实现 headless 的关键：主题用 `#root` 接管后，组件只负责行为与数据，主题拥有全部 HTML/CSS。

### 4.2 组件清单

| 组件 | props（`*` 必填） | root scope | 其他 slot | parts |
| --- | --- | --- | --- | --- |
| `blog-post-card` | `post*` | `{ post }` | `default { post }` | `bc-card*` |
| `blog-pager` | `pager*` | `{ pager, go, pages }` | — | `bc-pager` |
| `blog-state` | `state?`, `message?` | `{ state, message }` | — | `bc-state`, `bc-skeleton` |
| `blog-tag-chip` | `tag*` | `{ tag }` | — | `bc-tag` |
| `blog-image` | `src*`, `alt?` | `{ src, alt, open }` | — | `bc-image` |
| `blog-search-box` | `value?` | `{ value, search }` | — | `bc-search` |
| `blog-theme-toggle` | — | `{ colorMode, toggle, set }` | — | `bc-theme-toggle` |
| `blog-back-to-top` | — | `{ visible, scroll }` | — | `bc-back-to-top` |
| `blog-reading-progress` | — | `{ progress }` | — | `bc-reading-progress` |
| `blog-toc` | — | `{ items, activeId, scrollTo }` | — | `bc-toc` |
| `blog-tag-cloud` | — | `{ tags, state, message }` | — | `bc-tag-cloud`, `bc-tag`, `bc-tag-count`, `bc-state` |
| `blog-archives` | — | `{ archives, state, message }` | — | `archive-index`, `bc-state` |
| `blog-markdown` | `source?` | —（不给 root） | — | `bc-prose` |

`blog-markdown` 必须由组件渲染 Markdown（禁止 `v-html`），主题只能通过 `parts` 或外层容器定制；这是 2A 的唯一例外。

### 4.3 组件改造要点

* `blog-pager`：新增 `pages` 计算属性，`go(page)` 作为函数放进 scope；
* `blog-state`：`root` scope 暴露 `state` / `message`；
* `blog-search-box`：`search(value)` 作为函数放进 scope；
* `blog-theme-toggle`：scope 暴露 `colorMode`、`toggle`、`set`；
* `blog-back-to-top`：scope 暴露 `visible`、`scroll`；
* `blog-reading-progress`：scope 暴露 `progress`（0–1）；
* `blog-toc`：scope 暴露 `items`、`activeId`、`scrollTo(id)`；
* `blog-tag-cloud` / `blog-archives`：内部维护 `loading/failed/rows`，scope 暴露 `state` / `message`；
* `blog-image`：scope 暴露 `open()`；
* `blog-post-card`：保留现有 `default` slot，新增 `root`。

模板写法统一为：

```vue
<slot name="root" :pager="pager" :go="go" :pages="pages">
  <nav class="bc-pager">…默认结构…</nav>
</slot>
```

`scripts/theme-contract.mjs` 的 `emittedSlots` 会读取 `name` / `:prop`，因此 `check:contract` 能继续双向校验。

### 4.4 示例：主题接管分页

```html
<blog-pager :pager="pager" v-slot="{ pager, go, pages }">
  <nav class="my-pager" aria-label="分页">
    <button v-for="p in pages" :key="p" :disabled="p === pager.page" @click="go(p)">{{ p }}</button>
  </nav>
</blog-pager>
```

### 4.5 示例：主题接管主题切换

```html
<blog-theme-toggle v-slot="{ colorMode, toggle }">
  <button type="button" class="my-toggle" @click="toggle">
    {{ colorMode === 'dark' ? '浅色' : '深色' }}
  </button>
</blog-theme-toggle>
```

---

## 5. 默认实现与样式

* `runtime.css` 与组件默认 DOM **保留**：旧主题（没有 `root` slot）继续按原样渲染。
* 主题使用 `root` slot 时，默认 DOM 不渲染，`runtime.css` 的 `.bc-*` 样式自然不生效；此时主题拥有全部 HTML/CSS，等效“组件无样式”。
* `packages/theme-starter` 仍是默认主题与兜底实现；不要求重写。
* 新增测试 fixture `scripts/fixtures/theme-headless.html`，用 `root` slot 接管所有可接管组件，作为能力验收。

> 如果目标是“组件在任何情况下都不带样式”，那是 2B（删除默认 DOM/样式），不在本文范围。

---

## 6. 校验 v2

| 检查 | 现状 | v2 |
| --- | --- | --- |
| 组件 / 指令 / 标识符 | ✅ | ✅ |
| 体积 / `id="app"` / 禁用内容 | ✅ | ✅ |
| props 名 | ❌ | ⏳ 部分（已查必填 props；未知 props 暂未拦） |
| 必填 props | ❌ | ✅ |
| slot 名存在（含 `root`） | ❌ | ✅ |
| slot scope 字段 | ❌ | ✅ |
| 事件绑定到已登记事件 | ❌ | ✅ |
| 必需路由分支（`home` / `post`） | ❌ | ✅（`AiThemePrompt.validate`；手动保存暂未强制） |
| 字面量链接符合 `CONTRACT_URLS` | ❌ | ⏳ 待补（先由提示词约束，避免误判） |
| 主题 CSS 的 `.bc-*` 必须登记 | 仅构建期扫 starter | ✅（保存时也查） |
| CSS `</style>` 逃逸 | ❌ | ✅ |

静态校验无法判断“交互是否正确”，仍需运行时自检（`blog-preview:diagnose`）与默认主题兜底。

---

## 7. AI 接入

### 7.1 提示词结构

删除 `AiThemePrompt.CONTRACT_RULES` 与 `ALLOWED_CSS_VARS`，改为从 `theme-contract.json` 生成：

```text
[版本与硬约束]
[组件 cookbook：props / events / slots / scope / parts / example]
[数据类型：CONTRACT_TYPES]
[辅助函数与动作签名]
[路由与 URL]
[CSS 变量与指令]
[输出格式：完整主题 / 编辑补丁]
```

### 7.2 完整生成

* 输入：契约 + cookbook + 输出格式；
* 默认不注入 starter 全文，避免 starter 偏差；
* 需要示例时，只注入相关组件的 `example`，或用户显式选择“参考默认主题”。

### 7.3 后续迭代

* 继续使用当前主题 + 补丁 ops；
* 主题拥有 HTML/CSS 后，`replaceHtml` / `replaceCss` / `replace` 是合理操作；
* `setCssVar` 白名单从 JSON 读取。

### 7.4 预算

* 首次完整生成建议 `AI_MAX_TOKENS=65536`、`AI_THINKING=disabled`；
* `AI_TIMEOUT` 不低于 600s，SSE 总超时同步；
* 补丁轮沿用较小输出。

---

## 8. 生成、提交与同步

1. `pnpm sync:contract` 生成两份相同的 JSON：
   * `frontend/apps/blog/dist/theme-contract.json`（现有构建产物）；
   * `backend/src/main/resources/theme/theme-contract.json`（新增，随仓库提交）。
2. 后端启动时从 classpath 读取；缺失或不可解析 → 启动失败（fail-fast）。
3. `ThemeTemplateValidator`、`AiThemePrompt` 只读该 JSON；`SiteSettingCatalog` 可以直接读取，或由单测锁定与 JSON 一致（二选一，不能继续裸手写）。
4. CI / 本地校验：`pnpm typecheck` 内的 `check:contract` 会直接比较后端生成物与当前 `contract.ts`；不一致即失败，提示运行 `pnpm sync:contract`。`sync:contract` 负责重新生成。
5. JSON 禁止手改；修改契约只改 `contract.ts` / `types.ts` / `runtime.ts`。

---

## 9. 兼容性

* **不升 `RUNTIME_CONTRACT`**：新增 slot / 字段 / 路由属于 L1“只增”。
* **旧主题不受影响**：没有 `root` slot 时渲染默认 DOM，`runtime.css` 与 `theme-starter` 均不变。
* **JSON 只增字段**：现有消费者可忽略新字段。
* **数据库零迁移**：`site_theme_version.contract` 不变。

---

## 10. 实施顺序

1. **契约与生成器**：扩展 `contract.ts` / `types.ts`，修 §1.2 漂移，生成 JSON 并提交后端资源。
2. **组件 root slot**：先展示型（post-card / pager / state / tag-chip / image / search-box / theme-toggle / back-to-top / reading-progress），再数据型（toc / tag-cloud / archives）。
3. **校验器**：改为读 JSON，补 props / slot / scope / `.bc-*` / `</style>` 检查；`home` / `post` 分支在 AI 完整主题校验中强制，URL 前缀暂由提示词约束。
4. **AI 提示词**：删除手写清单，改为 JSON + cookbook。
5. **fixture 与评测**：`theme-headless.html` + 一组生成 prompt，记录通过率、耗时、token。
6. **文档合并**：将本文并回 `docs/blog-runtime-theme.md` §3，删除本文。

---

## 11. 验收

* `pnpm check:contract` 通过；生成的 JSON 已提交且无 diff。
* 13 个组件中，12 个有 `root` slot；`blog-markdown` 明确例外。
* `theme-headless.html` 用 `root` slot 接管所有可接管组件，校验与渲染均通过。
* 现有 `theme-starter` 渲染结果不变。
* `ThemeTemplateValidator` 能拦下：未知 slot、未知 scope 字段、缺必填 props、未知事件、`</style>`、未登记 `.bc-*`。
* `AiThemePrompt.validate` 能拦下缺少 `home` / `post` 分支；非法链接前缀暂未做静态校验（先由提示词约束）。
* `AiThemePrompt` 中不再出现手写的组件名 / 字段名 / CSS 变量清单。
* 评测：仅靠契约 + cookbook（不注入 starter）能生成可校验主题，并记录基线。

---

## 12. 风险与未决

1. **`root` 约定 vs `renderless` prop**：本文用 `root` slot 约定；若团队更倾向显式 prop，需要改契约。
2. **正则校验的边界**：props / slot scope 的解析是保守扫描，复杂表达式可能误判；沿用现有“无法确认就拒绝”的策略。
3. **`example` 字段**：是否给所有组件写示例；建议先给 slot 复杂的组件（post-card / pager / toc / tag-cloud）。
4. **列表重复**：主题仍要在首页 / 标签 / 归档 / 搜索重复卡片列表；是否新增 `blog-post-list` 需要单独评估（当前文档 §3.4 明确 `<blog-post-list>` 未实现）。
5. **长输出超时**：体积可接受，但 16–18K token 需要 600s+ 超时；否则失败方式会变成超时。
6. **构建顺序**：`mvn test` 不构建前端；因此后端 JSON 必须提交，不能依赖 `pnpm build:blog`。

---

## 13. 变更记录

| 日期 | 变更 |
| --- | --- |
| 2026-11-08 | 初稿：2A 增量 headless；契约投影；已知漂移清单；校验与 AI 接入方案 |
| 2026-11-08 | 阶段 1 实现：`contract.ts` / `types.ts` 扩展；`theme-contract.json` 生成到后端资源；`ThemeContract` 运行时投影；12 个组件 `root` slot；校验器/AI 提示词只读 JSON；headless fixture |
