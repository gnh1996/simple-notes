# 评估：Markdown 编辑器换 Vditor + 桌面端改 Wails v3

> **状态：Wails v3 已采纳并落地骨架；admin / blog 已切换 Vditor + Lute（方言 v2）；desktop 待迁移**
> 范围：评估与落地记录；桌面端功能设计另行推进。
> 关联：`docs/tech-stack.md`、`docs/markdown-dialect.md`、`docs/markdown-dialect-fixture.md`、`docs/blog-runtime-theme.md`、`frontend/AGENTS.md`、`desktop/README.md`

## 决策记录（2026-10-25 确认）

1. **内存**：可接受 WebView 带来的上移，但要求尽量优化 → Wails 提案通过；以实测数据替代旧 74MB 基线。
2. **编辑器**：写作体验与编辑器功能都在考虑范围内 → Vditor 不直接否决，先做 fixture POC。**POC 已执行（2026-10-25）**：暴露旧方言的 5 处冲突；随后产品决策改为 **v2 白名单**，admin / blog 已切换 Vditor + Lute，桌面端待迁移。
3. **Wails 版本**：接受 v3 beta → 锁定 `v3.0.0-beta.34`，预留 GA 升级窗口。

已落地：`desktop/`（Go + Wails 骨架）+ `frontend/apps/desktop`（Vue UI）；Windows 构建 / 运行已验证，内存实测见 §3.5。

## 0. 结论速览

两个提案应当**拆开决策**：它们只在"桌面端复用 Web 编辑器"这一点上相交，风险与成本完全不同。

| 提案 | 可行性 | 主要收益 | 主要代价 | 建议 |
| --- | --- | --- | --- | --- |
| 桌面端改 Wails v3 | **可行（beta 阶段）** | 复用 Vue/TS 资产；桌面端不再自带第三套 Markdown 引擎；Go 适合本地优先 / 后台同步 | WebView 内存基线；Wails v3 尚未 GA；Linux 依赖 GTK4 + WebKitGTK 6.0；放弃 NativeAOT 叙事 | **已采纳并落地骨架** |
| admin/blog 换 Vditor（仅编辑器） | 技术可行，**架构不成立** | IR / WYSIWYG 写作体验、中文语境好、维护活跃 | 渲染引擎由 markdown-it 变成 Lute，与 blog 不再同源，直接破坏"编辑器预览 = 发布所得" | **不推荐** |
| 三端统一走 Lute（Vditor 全量） | 可行，**admin / blog 已落地（2026-10-25）** | 真正单一引擎；编辑预览 = 发布；IR/WYSIWYG | 方言 v2（允许数学/emoji/安全 HTML）+ 主题 CSS + DOMPurify | **已执行；desktop 待迁移** |
| Wails + 保留 md-editor-v3 | 可行 | 桌面与 admin 共用同一编辑器与方言配置，方言引擎从 3 套降为 2 套；无方言迁移 | WebView 内存基线 | **当前落地路径** |

一句话：**Wails 值得做；Vditor 不是"换个编辑器"，而是"换掉整个方言引擎"，只有在接受方言 v2 的前提下才成立。**

---

## 1. 现状基线（评估依据）

| 端 | 当前实现 | 关键事实 |
| --- | --- | --- |
| admin | md-editor-v3 7.1.0（markdown-it 内核） | 仅 2 个编辑页使用 `MdEditor`（`views/notes/NoteEditorView.vue`、`views/posts/PostEditorView.vue`）；全局配置在 `packages/shared/src/markdown.ts`；样式从 `md-editor-v3/lib/*.css` 引入 |
| blog | blog runtime 内置 `markdown-it` 14.3.3 + github-alerts | 配置取自 `packages/shared`；构建产物 `dist/runtime.js` 约 **543KB**（未压缩）；CSP 已允许 `'unsafe-eval'`（Vue 运行时模板编译） |
| desktop | **评估时基线**：Avalonia 12.1 骨架 + 计划中的 LiveMarkdown.Avalonia（Markdig）；2026-10-25 已改为 Wails 骨架 | 当时尚无功能实现，只有占位窗口；NativeAOT 目标；无渲染栈内存基线 ~74MB（典型预估 90–140MB） |
| backend | 不解析 Markdown，OpenAPI 为契约单一来源 | **没有 CORS 配置**；JWT Bearer；blog 与 admin 分域 |
| Web 工作区 | pnpm workspace + catalog，`apps/*` 互不依赖 | 共享代码只能下沉到 `packages/*`；desktop 目前不在 workspace 内 |

由此得到两条关键判断：

1. **admin 的编辑器接入面很小**（两个页面 + 一个共享配置模块），换编辑器本身的成本不高；真正的成本在 blog 的渲染引擎与主题。
2. **desktop 当时还是骨架**，现在是换桌面技术栈成本最低的时刻；每拖一天，Avalonia 实现越多，切换越贵。

---

## 2. Vditor 评估

### 2.1 事实核验（2026-10-25）

| 项 | 结论 |
| --- | --- |
| 版本 / 维护 | **v4.0.1**（2026-10-18），4.x 首个稳定版 2026-08-30；近期修过媒体渲染 XSS（GHSA-x2mx-f527-mxr9）；周下载约 2.5 万 |
| 许可证 | Vditor MIT；渲染内核 **Lute = MulanPSL-2.0**（OSI 已批准，宽松许可） |
| 渲染内核 | **Lute**（Go 编译到 JS/WASM），不是 markdown-it；`preview` / IR / WYSIWYG 全部走 Lute |
| 编辑模式 | `sv`（v4 改为 textarea + 分屏预览）、`ir`（即时渲染）、`wysiwyg`；默认 `ir` |
| 静态资源 | Lute、highlight.js、图标、i18n、内容主题、Mermaid/KaTeX 等**默认从 unpkg 按需加载**，由 `options.cdn` 指定；离线/自托管必须显式配置 |
| 代码高亮 | Vditor 自己加载 `highlight.min.js`（hljs 11.7）并使用 **`window.hljs`**；额外语言只来自 `third-languages.js`（实测仅 solidity / yul / abap / hlsl / gdscript）；**没有注入自定义 hljs 实例的入口** |
| 体积 | Lute 官方口径：`lute.js` 约 5MB，gzip 约 500KB；Vditor 分发的 `lute.min.js` 需实测；完整 `vditor/dist` 还包含 emoji、图表、公式等大量静态资源 |

### 2.2 核心冲突：方言引擎从 markdown-it 变成 Lute

当前方言的"单一语义源"是 `packages/shared/src/markdown-dialect.ts`（`breaks/html/linkify` + github-alerts + hljs），admin 与 blog 都从它取配置。Vditor 的预览无法换成 markdown-it，因此：

> **一旦 admin 用 Vditor，admin 预览由 Lute 渲染，blog 仍由 markdown-it 渲染，"编辑器预览 = 发布所得"不再成立。**

这不是样式差异，而是方言契约的破坏。2026-10-25 的 POC 结果如下（完整数据见 §2.7）：

| 方言条目 | Lute / Vditor 实测 | 结论 |
| --- | --- | --- |
| CommonMark + GFM（标题/列表/表格/删除线/自动链接） | Lute 声称完整实现 GFM/CM | ✅ 基本可用 |
| 软换行 `breaks:false` | POC 未见结构性差异 | ⬜ 仍需与 blog 逐字比对 |
| GitHub Alerts（`> [!NOTE]` 等 5 种） | `<div class="callout" data-subtype="NOTE">` + emoji 图标 + 英文标题（blog 为 `markdown-alert-*` + SVG） | ⚠️ 语义对，DOM/CSS 契约不同 |
| Mermaid（承诺 4 种图） | Vditor 支持，资源经 cdn 加载 | ✅（需自托管 mermaid） |
| 裸 HTML 不入库 | `sanitize:true` 只挡 XSS；`<b>raw</b>` 原样保留 | ❌ 需自定义渲染器或放宽方言 |
| 数学公式（禁区） | 行内 `$x$` 可关闭；**块级 `$$...$$` 仍被 KaTeX 渲染** | ❌ 无配置项可关 |
| 脚注（禁区） | `footnotes:false` 下 `[^1]` 输出 `^1`（标记被消费） | ❌ 非纯文本 |
| 上下标（禁区） | `sub:false` 下 `H~2~O` 输出 `H2O`；`x^2^` 保持字面 | ❌ 标记丢失 |
| `==高亮==`（禁区） | `mark:false` 下保持字面 | ✅ |
| Emoji 短代码（禁区） | `:smile:` → 😄、`:+1:` → 👍 | ❌ 无关闭选项 |
| 图片尺寸语法（禁区） | 未测 | ⬜ |
| Front matter（禁区） | 未测 | ⬜ |
| 代码块语言集（附录 A） | `java` / `dockerfile` / `properties` 正常；**`vue` 无高亮**（hljs 无该语言，Vditor 不暴露自定义实例） | ❌ `vue` 承诺不成立 |

> 结论：Lute 是成熟的 GFM 引擎，但它默认带一批本项目明确列为"禁区"的扩展。POC 证明这不是"配置能对齐"的问题：数学块、emoji、裸 HTML、脚注/上下标在**解析阶段**就改变了内容，必须用自定义渲染器拦截（且要验证 AST 是否还保留原始标记）。

### 2.3 额外风险：源文本保真

`note.content` 存的是原始 Markdown，发布快照也基于它。Vditor 的 IR / WYSIWYG 是 contenteditable，`getValue()` 可能规范化文本。POC 实测（2026-10-25）：

- `sv` 模式：打开 → 保存，逐字节一致（textarea，源文本安全）；
- `ir` 模式：列表与表格之间会被补一个空行（二次编辑后稳定，不持续累积），但源文本已改变；
- WYSIWYG 未单测，按同一 Lute 管线推断风险不低于 IR。

若要求源文本严格保真，只能用 `sv` 模式，此时 Vditor 相对 md-editor-v3 的优势只剩工具栏 / 主题 / 上传等外围能力，**WYSIWYG 这一核心卖点不复存在**，切换意义大幅下降。

### 2.4 三端影响与工作量（量级：单人 + agent，含自测）

| 端 | 改动 | 量级 |
| --- | --- | --- |
| admin | 两个编辑页换组件；`packages/shared/src/markdown.ts` 重写为 Vditor options + 自托管 cdn；样式导入；上传回调（`onUploadImg` → `upload.handler`）；工具栏/暗色/全屏；主题联动 | **1–2 天** |
| blog runtime | `apps/blog/src/markdown.ts` 改为 Lute（`lute.min.js` 自托管）；标题锚点改由 Lute 生成；代码高亮改走 Vditor 的 hljs；callout / 表格 / 任务列表 HTML 结构变化；bundle 增大约 400–500KB gzip | **4–6 天** |
| **方言适配层** | 自定义渲染器：math block / emoji / 裸 HTML / sub / footnote 恢复为字面文本；hljs 补 `vue → xml` 别名；callout HTML 契约；每项都要 fixture 验证 | **2–4 天（风险最高）** |
| 默认主题 / 已发布内容 | `packages/theme-starter` 与后端兜底副本的 CSS 需适配 Lute 输出（`.vditor-reset` 或自定义 class）；已发布文章视觉回归 | **2–4 天** |
| 静态资源 | 自托管并裁剪 `vditor/dist`（emoji / KaTeX / ECharts / PlantUML 等未收资源不打包）；版本锁定 + 校验；admin public、blog jar、桌面端三处各自提供 | **1–2 天** |
| 文档 / fixture | 方言 §6 重写、附录 B 版本 +1、fixture 三端重跑并记录差异 | **1–2 天** |

**只换 admin、不换 blog 的"低成本方案"不成立**：它用 1–2 天的成本换来一个长期存在的"预览不等于发布"问题，违反了项目最核心的内容一致性约定。

### 2.5 可选路径

| 方案 | 说明 | 评价 |
| --- | --- | --- |
| A. 全端 Lute（方言 v2） | admin / blog / desktop 统一 Vditor + Lute；markdown-it、github-alerts、md-editor-v3 全部退役 | 唯一自洽的换法；成本集中在 blog 与主题；收益是真正的三端一致 + WYSIWYG |
| B. 仅 admin/desktop 用 Vditor | blog 继续 markdown-it | 破坏"预览 = 发布"，**否决** |
| C. 暂不换 | 保留 md-editor-v3；把 WYSIWYG 作为未来需求重新评估 | 风险最低；若 Wails 落地，桌面端可直接复用 md-editor-v3 |
| B'. Vditor 只当 textarea 工具栏 | `sv` 模式 + 隐藏自带预览，右侧用自己的 markdown-it 预览 | 技术上可行，但等于把 Vditor 降级成"带工具栏的 textarea"；只值得在其外围能力（上传/主题/i18n）确有价值时考虑 |

### 2.6 建议

- **不建议**把 Vditor 作为孤立的编辑器替换；POC 已证明它不是"配置能对齐"的编辑器，而是另一套方言引擎。
- 若产品上确实需要 Typora 式写作体验，按方案 A 立项，但立项前必须先做"自定义渲染器可行性"POC：证明 math / emoji / html / sub / footnote 能恢复成字面文本，再改动 blog。否则不要开始。
- 若只是为了"编辑器更好用/更好看"或工具栏功能，优先升级 md-editor-v3 / 补工具栏，不要动引擎。
- 若只想要 Vditor 的写作手感、又不想动方言：方案 B'（SV 模式 + 自有 markdown-it 预览）可保留源文本与方言，但等于放弃 IR/WYSIWYG，收益有限。

### 2.7 POC 清单与结果（2026-10-25 已执行）

**POC 条件**：Vditor 4.0.1 + 自托管 `cdn`（`/vditor`，无外部请求）+ 与 blog 相同的语义配置（`breaks:false` / `html:false` / github-alerts / `inlineMath:false` / `footnotes:false` / `sup:false` / `sub:false` / `mark:false` / `sanitize:true` / `autoSpace:false` / `fixTermTypo:false`）。样本覆盖标题、强调、alerts、数学、emoji、上下标、mark、脚注、裸 HTML、表格、任务列表、代码块。

> 下表按 **v1 方言**判定；2026-10-25 产品决策改为 **v2 白名单**（允许数学 / emoji / 安全 HTML / 脚注 / 上下标），因此这些“❌”已被接受，不再是阻塞。

| 验证项 | 实测结果 | 判定 |
| --- | --- | --- |
| 行内数学 `$x^2$` | `inlineMath:false` 下保持字面 `$x^2$` | ✅ |
| 块级数学 `$$...$$` | 即使 `mathBlockPreview:false`，仍由 KaTeX 渲染成公式 | ❌ 无处可关；需自定义渲染器 |
| Emoji `:smile:` / `:+1:` | 转成 😄 / 👍 | ❌ 方言要求纯文本；无关闭选项 |
| 裸 HTML `<b>raw</b>` | 保留为真实 `<b>`（`sanitize` 只挡 XSS；`<script>` 被移除） | ❌ 方言要求转义 / 移除；需自定义渲染器 |
| 脚注 `[^1]` | 标记被消费，输出 `^1` | ❌ 与"显示为纯文本"不符 |
| 上下标 `H~2~O` / `x^2^` | 下标标记被消费为 `H2O`；上标保持字面 | ❌ 部分标记丢失 |
| `==高亮==` | 保持字面 | ✅ |
| GitHub Alerts | Lute 输出 `<div class="callout" data-subtype="NOTE">` + emoji 图标 + 英文标题；blog 输出 `<div class="markdown-alert markdown-alert-note">` + SVG | ⚠️ 语义对，结构和 CSS 契约不同 |
| 代码高亮 | `java` / `dockerfile` / `properties` 正常；**`vue` 无高亮**（hljs 无该语言，且 Vditor 不暴露自定义实例） | ❌ 附录 A 的 `vue` 承诺不成立 |
| 任务列表 | Lute 渲染复选框；**当前 blog 的 markdown-it 未渲染复选框**（输出字面 `[ ]`） | ⚠️ 既有 gap，与 Vditor 无关，需单独修 |
| 源文本保真 | `sv` 模式逐字节一致；`ir` 模式会在列表与表格间补一个空行（二次编辑后稳定） | ⚠️ IR 会改源；要保真只能用 SV |
| 静态资源 | `lute.min.js` 3.74MB（未压缩）；完整 `vditor/dist` 23MB；默认走 unpkg，必须自托管 + 裁剪 | ⚠️ 可控但需专门处理 |
| 离线性 | 配置本地 `cdn` 后未发现外部请求 | ✅（本 POC 未抓完整网络，需在实现期加检查） |

**结论（2026-10-25 更新）：POC 冲突已通过方言 v2 解决，不再是阻塞。** 产品决定允许数学 / emoji / 安全 HTML / 脚注 / 上下标；admin / blog 已切换，桌面端待迁移。原“保留 v1、用自定义渲染器恢复字面文本”的 C-b 路线不再采用。

---

## 3. Wails v3 评估

### 3.1 事实核验（2026-10-25）

| 项 | 结论 |
| --- | --- |
| 版本状态 | **v3.0.0-beta.34（2026-10-20）**；v3 于 2026-08-02 从 alpha 转 beta；GA 里程碑原定 2026-09-15，已逾期（完成度 92%，剩 7 个 issue）；beta 承诺"桌面 API 稳定"，但仍有预发布缺陷与公告式变更 |
| 发布方式 | beta 为 nightly tag；`wails3` CLI 用 `go install ...@版本` 安装（无桌面二进制发布流水线）；**必须锁版本** |
| 平台 | Windows amd64/arm64（WebView2）、macOS 10.15+/11+、Linux **Ubuntu 24.04 基线，GTK4 + WebKitGTK 6.0**；GTK3/WebKit2GTK 4.1 走 `-tags gtk3`，**v3.1 移除** |
| 工具链 | Go **1.26**（beta.31 起模块声明；安装页仍写 1.24，以发布说明为准）+ CGO + 平台 WebView；**不支持交叉编译**，三平台各自构建 |
| 许可证 | MIT / ISC |
| 仓库现状 | 桌面端是骨架，尚无功能；此刻切换成本最低 |

关键判断：项目本身处于预发布快速迭代阶段，采用 beta 工具链与项目节奏并不冲突；但 Wails GA 会先于本项目上线，**升级成本大概率要付一次**，需锁版本并预留升级窗口。

### 3.2 与现有硬约束的冲突（必须先拍板）

`desktop/AGENTS.md` 的硬约束是：

> 内存优先：**不内嵌 WebView 做 Markdown 预览**；新增依赖先评估内存与包体积。

Wails 的整个 UI 就是 WebView，这不是"用 WebView 做预览"，而是"整个桌面端变成 WebView"。因此：

- 旧的 **~74MB 无渲染栈基线作废**；典型 WebView2 / WebKitGTK 应用的进程树内存会显著高于 Avalonia（量级通常在 150–300MB，必须实测，不能引用他人数字当结论）。
- "NativeAOT 单文件 / IsAotCompatible / JSON 源生成"这套约束随之失效，改为 Go 静态二进制 + 前端资源内嵌；这是**减法**，不是负担。
- 该约束属于"已冻结选型"，切换必须按变更决策处理：**先确认低内存是否仍是第一优先级**。若仍是，Wails 直接出局，保留 Avalonia；若 UI 复用 / 迭代速度 / 功能完整度优先，则明确记录"内存基线让位于 WebView"。

**这是整个评估的第一决策点，不能绕过。**

### 3.3 目标架构（若采纳）

```text
┌──────────────────────────── desktop/（Go module）────────────────────────────┐
│  main.go            应用装配：窗口 / 菜单 / 托盘 / 单实例 / 生命周期          │
│  internal/api       HTTP 传输：net/http + JWT（token 只在 Go 侧）             │
│  internal/store     SQLite（modernc.org/sqlite）+ 迁移（本地优先数据）         │
│  internal/auth      登录 / 刷新 / token 存储（沿用 docs/auth-rbac.md）        │
│  internal/native    文件对话框 / 打开外链 / 通知 / 拖拽                        │
│  web/dist           //go:embed 前端构建产物                                    │
└──────────────────────────────────────────────────────────────────────────────┘
        ▲ Wails service bindings（Go ↔ WebView）
        ▼
┌──────────── frontend/apps/desktop（Vite + Vue 3 + TS）──────────────────────┐
│  复用 packages/api（OpenAPI 生成的类型）                                       │
│  复用 packages/shared（方言配置 / 断点）                                       │
│  复用 packages/ui（设计 token）                                                │
│  复用 packages/editor（从 admin 抽出的编辑器封装：md-editor-v3 或 Vditor）     │
│  布局 / 视图按桌面场景单独实现（不直接复用 admin 页面）                        │
└──────────────────────────────────────────────────────────────────────────────┘
```

**布局与 embed 约束**：Go 的 `//go:embed` 不能引用 module 之外的路径。若 Vue 应用放在 `frontend/apps/desktop`（推荐，才能用 pnpm workspace / catalog / `workspace:*`），构建产物需在 `wails3 build` 前拷入 `desktop/web/dist`（Taskfile 里 `pnpm --dir frontend build:desktop` + copy，目录 gitignore）。备选是把 Go module 直接放在 `frontend/apps/desktop` 内与 `package.json` 并列，避免拷贝，但会让"前端工作区"和"桌面端"边界变模糊。

**API 访问（关键设计）**：WebView 的 origin 是 `http://wails.localhost`（Windows）或 `wails://wails`（macOS/Linux），后端目前**没有 CORS**。两条路：

| 方案 | 做法 | 评价 |
| --- | --- | --- |
| 直连 fetch | WebView 直接请求后端；后端加 CORS；token 进 JS/localStorage | 与"token 存 SQLite、不进 JS"的认证设计冲突；还需处理自定义 scheme 的 CORS；**不推荐** |
| Go 传输（推荐） | 暴露一个通用 `api.Request(method, path, body)` binding；Go 侧注入 Bearer、处理 401/重试/离线队列；前端把 `createApiClient` 的传输层换成 binding | 无 CORS；token 不出 Go；端点封装与 OpenAPI 类型全部复用；后续离线队列有天然落点 |

配套细节：

- **大文件不要走 IPC**：WebView2 的 IPC body 有约 2MB 限制（上游已加 512KB 分片，但仍应避免）；图片/附件用 Go 侧文件对话框取路径后直接上传，或走 `application.HandleStream`。
- **SPA 路由**：自定义 scheme 下 history 路由的 fallback 不如 HTTP 稳，桌面端建议 hash 路由或显式 fallback。
- **安全**：前端资源全部内嵌；禁止远程导航；`index.html` 自带 CSP；开发期 `wails3 dev` 的 IPC 已有来源校验（v2 已加固，v3 保持同等要求）。

### 3.4 收益

1. **UI 技术栈统一**：desktop 用 Vue 3 + TS，与 admin/blog 同一套工具链、同一份设计 token、同一套 API 类型；招聘/维护/agent 上手成本都下降。
2. **方言引擎从 3 套降为 2 套**：当前桌面端计划用 Markdig（LiveMarkdown），是方言验收的第三个引擎；Wails + md-editor-v3 后，桌面与 admin 完全同源，**不需要为桌面单独做 fixture**。这是容易被忽略的最大收益。
3. **本地优先更自然**：SQLite + 后台同步 + 离线队列放在 Go 侧，goroutine/context 比 C# 异步栈更直接；token 也留在 Go/SQLite，符合既有认证设计。
4. **NativeAOT 负担消失**：不再需要 `IsAotCompatible`、`JsonSerializerContext`、反射规避；Go 侧 `encoding/json` 无此问题。
5. **原生能力齐全**：v3 提供窗口/菜单/托盘/文件对话框/剪贴板/拖拽/通知/单实例/更新器，覆盖笔记类应用需求。
6. **包体通常更小**：Go 二进制 + 内嵌前端，一般 10–25MB 量级；Avalonia NativeAOT 单文件通常更大（需实测对比）。
7. **前端热更新体验好**：Vite HMR + `wails3 dev`，比 Avalonia 的 XAML 调试链路轻。

### 3.5 代价与风险

| 风险 | 说明 | 对策 |
| --- | --- | --- |
| **内存 / WebView 基线** | 整机内存显著高于 Avalonia；Linux WebKitGTK 往往更重 | **已实测（Windows）**：模板空壳 WS ≈361MB / 私有 ≈232MB；本骨架 WS ≈390–403MB / 私有 ≈254MB；`--disable-gpu` 私有 ≈191MB。Go 进程仅 ~38MB，主要开销在 WebView2 进程树 |
| **Wails v3 beta** | GA 逾期；beta 仍有 breaking 变更与偶发缺陷；CLI 只能 `go install` 锁版本 | 锁 `v3.0.0-beta.34`；隔离 v3 API 到少量文件；预留 GA 升级窗口；关注 changelog |
| **Linux 依赖面** | 默认 GTK4 + WebKitGTK 6.0；Ubuntu 24.04 之外需 `gtk3` tag（v3.1 移除）；AppImage 打包 WebKit 较麻烦 | 明确支持基线（Ubuntu 24.04+）；或提供 deb + 依赖声明；提前验证目标发行版 |
| **中文输入法** | WebKitGTK / Wayland 下 IME 是历史风险点；WebView2 通常较好 | 必须在实机验证（fcitx5 + Wayland、Windows 微软拼音、macOS） |
| **无交叉编译 + CGO** | 三平台需各自 runner / 容器；增加 CI 成本 | GitHub Actions 三平台矩阵；或本地按平台构建 |
| **WebView2 运行时** | Win11 自带；旧 Win10 需 bootstrapper（离线安装矛盾） | 明确最低系统；安装包内置 bootstrapper 或检测提示 |
| **CORS / origin** | 直连后端需改后端 CORS；自定义 scheme 的 origin 不是普通 HTTP | 采用 Go 传输，彻底绕开 |
| **IPC 载荷上限** | WebView2 ~2MB 限制（已有分片修复，但不适合大二进制） | 附件走文件路径/流；不把图片 base64 塞 binding |
| **embed 路径** | `//go:embed` 不能跨 module | 构建后拷入 `desktop/web/dist`（推荐）或 Go module 前置到前端应用内 |
| **开发服务器无图形环境** | 本仓库开发机无法跑 GUI | 同现状：构建/单测无头，GUI 与 IME 实机验证 |
| **分发/签名** | macOS 公证、Windows 签名、Linux 包格式都需要新流水线 | 个人项目可先出未签名产物；正式分发再补 |

### 3.6 备选对比

| 方案 | 成熟度 | 前端复用 | 内存 | 工具链 | 评价 |
| --- | --- | --- | --- | --- | --- |
| **Wails v3** | beta（GA 临近） | Vue/TS 直接复用 | WebView 基线 | Go 1.26 + CGO | 与既有 TS 资产最契合；接受 beta 风险 |
| Wails v2 | 稳定 | 同样可复用 | WebView 基线 | Go | 稳定但 API 代际落后，未来仍要迁 v3 |
| **Tauri 2** | 稳定 | Vue/TS 直接复用 | WebView 基线 | Rust | 生态/签名/更新器更成熟；团队需接受 Rust 与更慢的构建 |
| Avalonia（现状） | 稳定 | 不能复用 Web UI | **最低** | .NET 10 + AOT | 内存优先时仍是最优解；但 UI/编辑器要重写一套 |
| Electron | 稳定 | 直接复用 | 最高（自带 Chromium） | Node | 与低内存目标冲突，淘汰 |

### 3.7 迁移步骤（若采纳）

1. **决策**：更新 `docs/tech-stack.md` 桌面端表 + 变更记录；修订 `desktop/AGENTS.md`（内存约束改为"WebView 预算 + 实测门槛"）、`desktop/README.md`、根 `AGENTS.md` 的禁则；待定清单 #5 的"C# 客户端"作废（桌面复用 TS 契约），#7"桌面 Markdown 编辑库"作废。
2. **骨架**：`frontend/apps/desktop`（catalog 依赖、复用 packages）+ `desktop/` Go module（main / services / Taskfile / build 资源 / embed 目录）。
3. **传输层**：`packages/api` 抽出可注入 transport；实现 `api.Request` binding；登录、401 刷新、离线错误统一在 Go 侧。
4. **存储**：SQLite + 迁移（`modernc.org/sqlite` 纯 Go，避免额外 CGO 面）；token 表按 `docs/auth-rbac.md` 落位；数据目录用 OS 标准路径。
5. **编辑器**：抽出 `packages/editor`（包装 md-editor-v3，或后续 Vditor），admin 与 desktop 共用；方言配置仍从 `packages/shared` 取。
6. **CI/分发**：三平台构建矩阵；Linux 安装 GTK4/WebKitGTK 6.0 开发包；Windows WebView2 检测；macOS 公证（可延后）。
7. **验收**：见 §3.8。

### 3.8 POC 结果与验收门槛（2026-10-25）

**已落地并验证（Windows）**：

- `desktop/`（Go module）+ `frontend/apps/desktop`（Vue UI）；`wails3 build` 全链路通过，产出 `bin/SimpleNotes.exe`；
- 窗口 + 暗色主题 + md-editor-v3（复用 `packages/shared` 的 `configureMarkdown()`）；
- Go services：`api.Do`（Bearer 注入、无 CORS）、`system.Status`（版本 / API 地址 / 本地库 / 令牌状态）；
- SQLite（`modernc.org/sqlite`）建库、KV 读写与持久化单测；
- 运行后数据库落在 `%APPDATA%\simple-notes\desktop.db`。

**实测数据**（空闲 20s，按进程树统计）：

| 场景 | 工作集合计 | 私有内存合计 |
| --- | --- | --- |
| Wails 官方模板（无编辑器） | ≈361MB | ≈232MB |
| 本骨架（md-editor-v3） | ≈390–403MB | ≈254MB |
| 本骨架 + `--disable-gpu` | ≈388MB | ≈191MB |

**建议门槛**（写进 `desktop/AGENTS.md`）：私有内存 ≤300MB、工作集 ≤450MB；当前默认参数在边界内，`--disable-gpu` 作为可选优化（会牺牲滚动 / 动画流畅度）。
后续优化：编辑器路由级延迟加载、WebView2 `DisabledFeatures` / `AdditionalBrowserArgs` 裁剪、减少常驻面板。

**尚未验证**（需实机 / 后续）：

1. 中文输入法（Windows 微软拼音、Linux fcitx5 + Wayland、macOS）；
2. Linux（GTK4 + WebKitGTK 6.0）/ macOS 构建与运行；
3. 登录 / 401 刷新 / 离线队列；
4. 大图上传与附件通道（应走 Go 文件路径 / 流，不走 IPC）；
5. 打包产物（NSIS / deb / AppImage / .app）与干净环境运行。

---

## 4. 编辑器、渲染引擎与桌面架构的耦合

### 4.1 先澄清两个历史因果

1. **旧桌面方案（Avalonia + AvaloniaEdit/LiveMarkdown）不是"md-editor-v3 不达标"导致的。**
   它是"低内存 / 不内嵌 WebView / NativeAOT"这条平台约束的结果。md-editor-v3 是 Vue 组件，从一开始就不可能直接跑在 Avalonia 里，因此它从来不是桌面端的候选；它只服务 admin。
2. **blog 从 `MdPreview` 切到内置 `markdown-it`，也不是"换引擎"，而是"换 DOM/主题归属"。**
   两者内核都是 markdown-it；当初弃用 `MdPreview` 的原因是组件把 DOM/CSS 与编辑器绑定、不利于数据化主题和移动端单套 DOM，而不是 markdown-it 不满足方言。因此这次切换**不能**被引用为"编辑器换、渲染管线就得换"的先例。

### 4.2 真正成立的耦合关系

| 关系 | 强度 | 说明 |
| --- | --- | --- |
| **渲染引擎 ↔ 发布渲染端** | **强** | 编辑器预览与 blog 必须同一引擎，否则"预览 = 发布"不成立。Vditor = Lute，所以用 Vditor 就必须让 blog 也走 Lute；md-editor-v3 = markdown-it，blog 可以继续 markdown-it。 |
| **桌面壳 ↔ 编辑器运行时** | **强** | Avalonia 只能跑原生编辑器（AvaloniaEdit）；任何 Vue 编辑器（md-editor-v3 / Vditor）都需要 WebView——Wails / Tauri / Electron，或"只给编辑器塞一个 WebView"（更差）。 |
| **桌面壳 ↔ 内存** | **强** | Wails = WebView 基线（Windows 实测 ≈390MB WS / 254MB 私有）；Avalonia = 原生基线（预估 90–140MB）。这是平台选择，不是编辑器选择。 |
| **编辑器组件 ↔ 方言** | **仅当引擎变化时成立** | md-editor-v3 与 blog 同为 markdown-it，换组件（比如换一个 markdown-it 内核的编辑器）不需要改方言；Vditor 换了内核，才需要方言 v2。 |
| **桌面壳 ↔ 编辑器引擎** | 无必然关系 | Wails 可以装 md-editor-v3，也可以装 Vditor；桌面壳不决定引擎。 |

由此得到三条判断：

- **"换 Vditor"必须连带换 blog 渲染**（引擎变了）；只换 editor、不换 blog 是架构上不成立的。
- **"换 Vditor"在桌面端必须连带换壳**（Vue 组件需要 WebView）；但反过来，换壳不等于必须换 Vditor。
- **"md-editor-v3 不满足条件"要先说清是哪个条件**：
  - 如果是 **WYSIWYG / Typora 式输入**：它确实不满足，Vditor 是候选；但 POC 证明代价是方言 v2 + 自定义 Lute 渲染器 + blog 迁移。
  - 如果是 **主题 / 移动端 / DOM 控制**：那是 blog 的 `MdPreview` 问题，已由 markdown-it runtime 解决，与 editor 库无关。
  - 如果是 **原生 / 低内存**：所有 Web 编辑器都不满足，只能留在 Avalonia + 原生编辑器。

### 4.3 组合矩阵（按"引擎"而非"组件"排列）

| 组合 | 编辑器 | 发布渲染 | 桌面壳 | 引擎数 | 结论 |
| --- | --- | --- | --- | --- | --- |
| **A. 旧路径** | md-editor-v3 | markdown-it | Wails | 2（admin/desktop 同源 + blog） | 无 WYSIWYG；UI/类型复用最好；内存高 |
| **B. 旧方案** | AvaloniaEdit + LiveMarkdown | markdown-it | Avalonia | 3（Markdig + markdown-it ×2） | 无 WYSIWYG；内存最低；UI/编辑器全部自研 |
| **C. 全端 Lute（当前）** | Vditor | Lute | Wails | 1 | **admin / blog 已落地（2026-10-25）；desktop 待迁移** |
| **D. 半套 Lute** | Vditor | markdown-it | Wails 或 Avalonia | 2–3 | 预览 ≠ 发布；**否决** |
| **E. Avalonia + WebView 编辑器** | Vditor / md-editor-v3 | 对应引擎 | Avalonia + WebView | 2–3 | 两套 UI + WebView 内存；**否决** |

### 4.4 优先级重估：如果"编辑体验"是项目核心（2026-10-25 追加）

> **执行状态（2026-10-25）**：已按 C-a 落地 admin / blog（Vditor + Lute + 方言 v2 + DOMPurify）；桌面端待迁移。

前面的结论把"方言稳定 / 安全边界"放在第一位，因此 Vditor 的 POC 失败被当作否决理由。若项目的第一优先级是**编辑体验**，权重需要反过来：

> 先澄清一个容易混淆的点：**A 组合（Wails + md-editor-v3 + blog markdown-it）已经没有"方言转换"**——admin / desktop / blog 三端同为 markdown-it，配置来自 `packages/shared`，存的是原始 Markdown，发布时同引擎渲染。Vditor 的增量价值是 **IR/WYSIWYG 的编辑体验 + 单一编辑器组件**，不是"修复转换"；如果分屏编辑可以接受，A 已经满足"多端统一 + 编辑即发布"。

1. **"统一编辑器"比"维持现有方言"更重要时，Vditor 全端（路线 C）是更贴合目标的形态。**
   - admin / desktop / blog 共用 Lute 一个引擎；admin/desktop 共用同一个 Vditor 组件与配置；blog 只读，使用同一 Lute 渲染（不是同一个编辑器 UI，但同一引擎/同一输出）。
   - 编辑预览 = 发布结果，不再有"编辑器一套、发布一套"的心智负担。
2. **"无需方言转换"要精确理解**：统一引擎后确实没有"两套引擎之间的转换"，但仍需要一份方言规范——它由 Lute 的行为定义（方言 v2），而不是消失。
3. **Vditor 的 POC 结果应转换为"产品决策清单"，而不是否决票**：
   - 是否允许数学公式（块级数学会被渲染）？
   - 是否允许 emoji 短代码（会变成 😄）？
   - 是否允许安全的行内 HTML（`<b>` 会保留，`<script>` 会被清理）？
   - 是否接受脚注/上下标标记被规范化（`[^1]` → `^1`、`H~2~O` → `H2O`）？
   - 是否接受 IR 模式对源文本做轻微规范化（SV 模式可逐字节保真）？
   这五项是产品语义，不是 bug。接受其中大部分，就能走"Lute-native 方言 v2"，几乎不需要自定义渲染器；坚持全部禁止，才需要自定义渲染器。

因此有两条 Vditor 落地子路线：

| 子路线 | 做法 | 成本 | 适用 |
| --- | --- | --- | --- |
| **C-a. Lute-native 方言 v2（推荐先评估）** | 接受数学 / emoji / 安全 HTML / 脚注等 Lute 原生语义；blog 同步换 Lute；主题 CSS 适配 callout/math/code | 低–中；主要成本在 blog 主题与安全清洗 | 编辑体验优先，且这些语法对博客是加分项 |
| **C-b. Lute + 自定义渲染器** | 用 `SetJSRenderers` 把 math / emoji / html / sub / footnote 恢复成旧方言要求的字面文本，再补 `vue` 高亮 | 中–高；版本敏感、需专项 POC | 旧方言是硬约束（例如安全审查要求"零裸 HTML"） |

**安全前提**（C-a 必须补）：允许行内 HTML 就必须有真正的清洗链路（后端入库清洗 + 前端渲染前 sanitize），不能只依赖 Lute 的 XSS 过滤；这是把"禁区"改成"白名单"的必要成本。

**桌面端结论不变**：如果目标是"多端统一编辑器 + 快速原型"，Wails 是正确选择——它让 admin/desktop 复用同一个 Vditor 组件与 Lute 配置；内存代价（§3.8）是已接受的取舍。Wails 不再是"为了原型临时用"，而是"统一 Web 编辑器的自然载体"。

### 4.5 旧结论（方言优先视角，已被 §4.4 重估）

- **如果编辑体验是核心**：目标形态是 **C（全端 Lute）**，且 admin / blog 已于 2026-10-25 落地；桌面端待迁移。Wails 是统一 Web 编辑器的自然载体。
- **如果编辑体验不是核心**：A（md-editor-v3 + markdown-it）仍自洽，但当前已按编辑体验优先切换，不再回退。
- **如果低内存重新成为第一优先级**：B 仍成立；桌面端仍是骨架，回退成本低。
- 最需要避免的是 **D/E**：既保留 markdown-it 的发布侧、又让编辑侧跑 Lute，或为了一个编辑器同时维护两套 UI。

> 方言优先视角的一句话：**编辑器换不换，取决于引擎换不换；桌面壳换不换，取决于要不要 Web UI；这两件事只在"用 Vditor"时同时发生，不能互相替代论证。**

> 编辑体验优先视角的一句话：**先决定愿意放宽哪些方言；如果愿意接受 Lute 的语义，Vditor + 全端 Lute + Wails 就是主路径，而不是"Vditor 未通过"。**

---

## 5. 建议的决策顺序

1. ~~先回答"低内存是否仍是第一约束"~~ → **已确认：接受上移（§3.8 实测），Wails 通过**。
   - 如果编辑体验是核心：Wails 是"统一 Web 编辑器（Vditor）"的自然载体，值得长期保留。
   - 如果编辑体验不是核心：Wails 的价值只剩 UI / 类型复用，应与 Avalonia（内存更低）重新比较；桌面仍是骨架，回退成本很低。
2. ~~Wails v3 POC~~ → **已完成骨架（2026-10-25）**；待补 Linux/macOS、IME、打包验收。
3. ~~Vditor POC~~ → **已执行并落地（2026-10-25）**：旧方言冲突通过 **v2 白名单**（允许数学/emoji/安全 HTML/脚注/上下标）解决；admin / blog 已切换，桌面端待迁移。
4. **桌面端迁移**：待功能设计确定后，把 md-editor-v3 换成 Vditor + `@simple-notes/markdown`，并补 v2 fixture 第三端。

---

## 6. 待确认 / 后续问题

已确认（2026-10-25）：内存可接受上移、写作体验与编辑器功能都重要、接受 Wails v3 beta、桌面端采用 Web 技术栈 UI。

仍待确认：

1. **编辑体验是否第一优先级**？若是，Vditor 全端（C）是主路径；若不是，A/B 更稳。
2. **方言 v2 允不允许数学 / emoji / 安全行内 HTML / 脚注**？允许 → C-a（最快）；全部禁止 → C-b（自定义渲染器）。
3. Wails 的定位：是"统一 Web 编辑器的长期载体"，还是"只做一版原型"？前者保留；后者可先按原型约束，验证后再决定是否回退 Avalonia。
4. Linux 支持基线是否可定为 Ubuntu 24.04+（GTK4 + WebKitGTK 6.0）？
5. 内存预算是否按 §3.8 的"私有 ≤300MB / WS ≤450MB"执行？是否需要在设置里提供"低内存模式（`--disable-gpu`）"？
6. 是否先修当前 blog 任务列表渲染缺失（markdown-it 未输出复选框）——若迁移到 Lute 会自动解决，但迁移前 fixture 会暴露。

---

## 7. 若批准：文档与代码变更清单

**文档**

- `docs/tech-stack.md`：桌面端表（Wails / Go / SQLite / 编辑器）、变更记录、待定 #5 / #7；
- `desktop/AGENTS.md`、`desktop/README.md`：技术栈、命令、内存门槛、平台基线、IME 验证项；
- 根 `AGENTS.md`：禁则"不内嵌 WebView"改为"WebView 内存预算 + 实测量级"，命令速查更新；
- `docs/markdown-dialect.md`（仅方案 A）：引擎表、§6 配置清单、附录 B 版本；
- `docs/markdown-dialect-fixture.md`：三端记录表补 Lute 结果；
- `frontend/AGENTS.md` / `frontend/README.md`：桌面前端应用、`packages/editor`、catalog 变更。

**代码**

- 新增 `frontend/apps/desktop`（Vue + Vite + TS）；
- 新增 `desktop/` Go module（Wails v3，锁 beta 版本）；
- `packages/api`：transport 可注入；新增 binding 传输实现；
- `packages/editor`：从 admin 抽出的编辑器组件（md-editor-v3；方案 A 时替换为 Vditor）；
- ~~删除 `desktop/src/*`（Avalonia 骨架）与 `.slnx`~~（已删除，2026-10-25）；
- CI：三平台构建矩阵 + Go / Node 版本锁定；Linux 依赖安装。

---

## 参考

- Wails v3 状态与路线图：<https://v3.wails.io/status/>（Last updated: 2026-10-20）
- Wails v3 安装与平台依赖：<https://v3.wails.io/getting-started/installation/>
- Wails v3 changelog（beta.34 / Go 1.26 / GTK4+WebKitGTK 6.0）：<https://v3.wails.io/changelog/>
- Wails v3 Services / bindings：<https://v3.wails.io/features/bindings/services/>
- Vditor changelog（4.0.1 / 4.0.0）：<https://github.com/Vanessa219/vditor/blob/master/CHANGELOG.md>
- Vditor options / preview：<https://github.com/Vanessa219/vditor>
- Lute（引擎、许可证、体积）：<https://github.com/88250/lute>
- Vditor highlightRender 源码（`window.hljs`）：<https://github.com/Vanessa219/vditor/blob/master/src/ts/markdown/highlightRender.ts>
