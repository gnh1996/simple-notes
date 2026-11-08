# 技术选型总览

> **冻结日期：2026-10-18**（第一阶段选型收口）
> 变更机制见文末。相关文档：`docs/content-model.md`、`docs/auth-rbac.md`、`docs/markdown-dialect.md`、各端 README 选型表。

## 后端

| 项 | 结论 |
| --- | --- |
| 语言 / 框架 | Java 25 + Spring Boot 4.1.1（WebMVC） |
| 构建 | Maven（wrapper）；依赖版本走 `spring-boot-dependencies` BOM 导入，不继承 `spring-boot-starter-parent` |
| 坐标 / 包名 | groupId `cc.naihao`；基础包 `cc.naihao.simplenotes`，启动类 `SimpleNotesApplication` |
| 数据层 | PostgreSQL + JPA（QueryDSL 类型安全 / 动态查询）+ Flyway 迁移 |
| 对象存储 | Cloudflare R2（S3 兼容）；私有 bucket + 媒体域 Nginx `auth_request` 预签名代理（见 `docs/attachment-storage.md`） |
| 本地配置 | 非机密在 `application.yml`；机密在 `application-local.yml`（不入库，模板入库）；部署用环境变量覆盖 |
| 安全 | Spring Security；认证机制已定：JWT + 服务端会话（三端统一 Bearer；`docs/auth-rbac.md`） |
| API 契约 | OpenAPI 导出 → 生成 Web(TS) 客户端；桌面端复用同一 TS 类型（Go 侧只做通用 HTTP 转发） |

## Web（`frontend/`，pnpm workspace）

| 项 | 结论 |
| --- | --- |
| 基础栈 | Vue 3.5 + Vite 8 + TypeScript 6 + vue-router 5 + pinia 4 |
| 样式 | Tailwind CSS v4 + 共享设计 token（`packages/ui`）；Element Plus 样式按 CSS 层导入（见 `frontend/AGENTS.md`） |
| 管理端 UI | Element Plus 2.14 |
| API 客户端 | 后端 springdoc 导出 OpenAPI → `packages/api` 用 **openapi-typescript** 生成类型（`pnpm gen:api`）+ 自建轻量 fetch 封装（传输层与 Bearer 注入在应用侧） |
| Markdown 编辑器 | **Vditor 4.0.1**（Lute 内核；admin / desktop 使用，三端同源） |
| Markdown 渲染 | 博客前台由 blog runtime 使用 **Lute**（`@simple-notes/markdown` + `method.min.js`），与 admin 共用 `vditorMarkdown`；见 `docs/blog-runtime-theme.md` |
| 响应式 | `useBreakpoint()` 单一断点来源，展示层分叉 |

## 桌面端（`desktop/` + `frontend/apps/desktop`）

| 项 | 结论 |
| --- | --- |
| 基础栈 | Go 1.26+ + **Wails v3.1.0**（GA 线；WebView2 / WKWebView / GTK4 + WebKitGTK 6.0） |
| UI | Vue 3.5 + Vite 8 + TypeScript 6（pnpm workspace 成员，复用 `packages/*`） |
| 架构 | Wails services / bindings；Go 侧负责窗口、HTTP 传输、SQLite、原生能力 |
| 数据模式 | 本地优先（SQLite；离线可用 + 后台同步） |
| Markdown | **Vditor 4.0.1 + Lute**（与 admin / blog 同源，方言 v2；资源自托管 `/vditor`，粘贴清洗）；2026-10-31 迁移完成 |
| 分发 | 三平台各自构建（Wails Taskfile / CI 矩阵）；不再使用 NativeAOT 单文件 |
| 内存基线 | Windows 实测（2026-10-31 验收，进程树）：列表 ≈364MB WS / ≈225MB 私有；打开编辑器 ≈424MB / ≈274MB；`--disable-gpu` 列表私有 ≈154MB、编辑态 ≈198MB；启动到 WebView 就绪 0.6–0.9s；详见 `desktop/README.md` |

## 跨端

| 项 | 结论 |
| --- | --- |
| 内容方言 | **v2 已落地（2026-10-25）**：Lute / Vditor；允许数学、emoji、脚注、上下标、mark、安全行内 HTML；见 `docs/markdown-dialect.md` |
| API 契约 | OpenAPI 单一来源，全端使用生成类型（桌面端经 Go binding 转发） |

## 待定清单（选型性质，随设计 / 实现推进确定）

1. **认证机制**（已定，2026-10-18）：JWT + 服务端会话记录（存数据库）；桌面端令牌存本地 SQLite（不采用系统钥匙串）——详见 `docs/auth-rbac.md`
2. **附件 / 图片访问链路（已定，2026-10-31）**：私有 Cloudflare R2 + 独立媒体域 Nginx；方案 B：`/oss/{key}` → Nginx `auth_request` → 后端短期预签名 → Nginx 代理 R2；本地 / DB 走 `/api/media/{id}`；三介质、任意文件、20MB、DIRECT 外链见 `docs/attachment-storage.md`（v1.2 已实现；A 预签名 302 作为回退）
3. **部署形态**：admin 挂 `/admin` 子路径 vs 子域（影响 vite `base` 与资源 / 代理配置；认证已不依赖 Cookie，无跨域 Cookie 约束）
4. **桌面本地存储实现**（已定，2026-10-25）：SQLite + `modernc.org/sqlite`（纯 Go，无额外 CGO）；表结构由 `internal/store/migrations` 的有序迁移维护（`PRAGMA user_version`），版本与备份规则见 `docs/desktop-local-schema.md`
5. **API 客户端生成工具**（Web 端已定，2026-10-18）：**openapi-typescript**（仅生成类型到 `packages/api/src/generated`，请求侧沿用 `packages/api` 的轻量 fetch 封装）；契约来自后端 springdoc 的 `/v3/api-docs`（`pnpm gen:api` 拉取生成）。桌面端复用同一份 TS 类型，由 Go transport 转发（C# 客户端方案作废，2026-10-25）
6. **工程化**：Vitest、ESLint/Prettier、后端 Testcontainers、机密防泄漏扫描（gitleaks 等）随实现补 —— **后端 Testcontainers 已补**（2026-10-18，随 RBAC 集成测试起用：`spring-boot-testcontainers` + `testcontainers-postgresql`，`@ServiceConnection` 注入）；其余待补
7. **桌面 Markdown 编辑库**（已定，2026-10-31）：桌面端已迁移到 Vditor 4.0.1 + Lute，与 admin / blog 同源；md-editor-v3 / markdown-it 在桌面端退役，方言 v2 三端一致。见 `docs/markdown-dialect.md`
8. **logback CVE-2026-19880 复查**：1.5.x 线无修复版，待 Spring Boot 升级至 logback 1.6.x 或 1.5.x 发布修复版后复核（详见变更记录）

## 变更机制

- "冻结"= 默认不再回头讨论，除非有新信息（许可证变化、兼容性问题、维护停更等）
- 变更流程：更新本文件 + 记录理由；相关细节文档（方言、README）同步更新
- 已发生的变更记录可追加到本文件文末

## 变更记录

### 2026-11-07 · 主题 AI 对话 v1（Spring AI 2.0，见 `docs/theme-ai.md`）

- **形态**：admin 端 `AI 对话` 抽屉（手写 SSE + artifact 卡片，不引入聊天 UI 依赖）；后端 `AiThemeService` 编排流式生成 + 校验修复循环（`token / round / done / error`），无服务端记忆、无 tool calling
- **依赖**：新增 `spring-ai-bom:2.0.2`（`dependencyManagement` 独立 import）+ `spring-ai-starter-model-openai`（Apache-2.0）；`spring.ai.model.chat=none` 关自动装配，模型懒加载手工构建（key 缺失 fail-soft）
- **配置**：`simple-notes.ai.*`（`AI_BASE_URL / AI_API_KEY / AI_MODEL / AI_TIMEOUT / AI_MAX_ROUNDS / AI_MAX_HISTORY_TURNS`），模板进 `application-local.yml.example`；密钥不入库不落日志
- **预览**：`AiDraftCache`（Caffeine，TTL 对齐 `preview-ttl`，无 Flyway）+ `BlogPageController` 的 `previewDraft` 分支；定版复用 `createVersion` + 人工 `activate`
- **验证**：`AiThemePromptTest`（抽取/校验/默认主题回归）+ 后端编译 + admin/api `typecheck` 全绿

### 2026-11-04 · 桌面端 Wails v3 beta.34 → v3.1.0（GA 线）

- **形态**：`desktop/go.mod` 的 `github.com/wailsapp/wails/v3` 由 `v3.0.0-beta.34` 改为 **`v3.1.0`**；`@wailsio/runtime` catalog 同步 `3.1.0`；CLI 安装命令改为 `go install .../wails3@v3.1.0`。**无一行 Go / 前端源码改动**
- **背景**：Wails v3 GA 为 `v3.0.0`（2026-10-26），`v3.1.0`（2026-11-01）是 GA 后第一条周更线。官方发布节奏：minor 号随时间递增（非功能里程碑）、patch 为 hotfix、nightly 预演下一个周更；beta 线已停止修补
- **理由**：① 兑现 2026-10-25「预留 GA 升级窗口」；② 命中本项目实际路径的修复——Windows `applyDarkMode` / 菜单栏 `SetTextColor` 崩溃、WebView2 省略 `Content-Length` 时保留请求体（`/__media` POST 导入）、资产服务 404 / HEAD / 转义路径路由、事件监听器投递顺序与 `Emit` hook 数据竞争
- **验证（2026-11-04，本机 Windows）**：`go build` / `go vet` / `go test ./...` 全绿；`wails3 generate bindings` 产出与既有 `bindings/` **逐字节一致**（295 packages / 9 services / 50 methods / 23 models）；`wails3 build` 全链路通过；`pnpm typecheck` 通过；依赖仅 +`ebitengine/purego`、+`golang.org/x/term`，−`adrg/xdg`、−`go-colorable`；同 flag 产物 26.6 → 26.8MB
- **版本策略**：锁 `v3.1.0`，只跟 hotfix / 安全公告，不追每个 weekly；升级时先跑 `wails3 task bindings` 确认 bindings 无 diff
- **文档**：`AGENTS.md`、`desktop/AGENTS.md`、`desktop/README.md`、`docs/tech-stack.md`、`docs/tech-eval-vditor-wails.md` 同步

### 2026-10-31 · 桌面端切换 Vditor + Lute（方言 v2 三端同源）

- **形态**：`frontend/apps/desktop` 编辑器由 md-editor-v3（markdown-it）改为 **Vditor 4.0.1 + Lute**，配置来自 `@simple-notes/markdown` 的 `vditorMarkdown` / `createEditorOptions` / `sanitizeHtml`；Vditor 资源经 `pnpm sync:vditor` 自托管到 `public/vditor`（9.4MB），禁用公共 CDN
- **理由**：三端同源，编辑器预览 = 发布所得；方言 v2 验收可覆盖桌面端
- **代价（实测，Windows 11 / WebView2 进程树）**：工作集 413.5 → 437MB、私有 261.6 → 298MB（+24 / +37MB）；exe 17.9 → 24.8MB（嵌入资源）；`--disable-gpu` 私有 298 → 209.8MB
- **清理**：`packages/shared` 中 md-editor-v3 专用的 `configureMarkdown` / `markdownDialect` 随之删除；桌面端新增 `MarkdownEditor.vue`
- **文档**：`docs/markdown-dialect.md` v2.1、`desktop/README.md`、`desktop/AGENTS.md`、`frontend/AGENTS.md` 同步

### 2026-10-25 · admin / blog 切换 Vditor + Lute，方言 v2 落地

- **形态**：管理端编辑器由 md-editor-v3 改为 **Vditor 4.0.1**（IR 模式，可切 SV）；博客 runtime 由 markdown-it 改为 **Lute**（`@simple-notes/markdown` + `method.min.js`，不打包完整编辑器）；两端共用 `packages/markdown/src/index.ts` 的 `vditorMarkdown`
- **理由**：编辑体验是第一优先级；统一引擎后编辑预览 = 发布结果，不再需要跨引擎对齐；Vditor 的 IR / WYSIWYG 是 md-editor-v3 的主要缺口
- **方言 v2**：允许数学（KaTeX）、emoji、脚注、上下标、mark、**安全行内 HTML**；HTML 在所有前端渲染入口用 DOMPurify 清洗；后端仍只存原文、不解析不渲染；可信作者模型
- **资源**：`frontend/scripts/sync-vditor-assets.mjs` 裁剪并复制 Lute / hljs / KaTeX / Mermaid / icons / i18n / 内容主题到各应用 `public/vditor`（约 9.4MB raw），禁止公共 CDN；MathJax / Graphviz / ECharts / PlantUML 等不打包
- **代码高亮**：自托管 hljs 补 `vue → xml` 别名（`third-languages.js`），附录 A 的 `vue` 承诺继续成立
- **代价**：blog runtime 由 543KB raw 降至约 291KB（gzip ≈102KB），但 Lute / hljs / KaTeX / Mermaid 作为 `/blog/vditor/**` 外部资源按需加载；首屏增加约 0.6–0.9MB gzip；主题 CSS 需适配 `.callout` / KaTeX / 任务列表
- **桌面端**：暂不迁移，仍为 md-editor-v3 过渡；不参与 v2 方言验收（见 `docs/markdown-dialect.md` §6）

### 2026-10-25 · 桌面端改为 Wails v3（Go + WebView），编辑器 Vditor 进入 POC

- **形态**：桌面端由 .NET 10 + Avalonia 改为 **Go 1.26 + Wails v3.0.0-beta.34**；UI 为 `frontend/apps/desktop`（Vue 3 + Vite，pnpm workspace 成员），Go module 在 `desktop/`，前端产物同步进 `desktop/web/dist` 供 `//go:embed`
- **理由**：① 复用 Vue/TS、`packages/api`、`packages/shared`、`packages/ui`，实现成本与 UI 质量更可控；② 桌面端不再自带第三套 Markdown 引擎（原计划 Markdig），改为与 admin 同源，方言引擎由 3 套降为 2 套；③ 本地优先（SQLite + 后台同步 + token）落在 Go 侧，与 `docs/auth-rbac.md` 的 token 存储设计一致；④ NativeAOT / 源生成 JSON 约束随之取消
- **代价**：旧"不内嵌 WebView"硬约束作废；内存基线显著上移（Windows 骨架实测 WS ≈390MB、私有 ≈254MB，`--disable-gpu` 私有 ≈191MB）；Linux 需 GTK4 + WebKitGTK 6.0，三平台各自构建；Wails v3 仍是 beta
- **实现**：Go 侧提供通用 `api.Do` binding（Bearer 注入、无 CORS、token 不进入 JS）；WebView 不直连后端；大文件 / 附件走 Go 侧路径或流，不走 IPC 大载荷
- **编辑器**：桌面端复用 md-editor-v3 + `packages/shared`（已可构建）；Vditor 4 / Lute 的 POC 已于 2026-10-25 执行（暴露方言冲突；随后按“编辑体验优先”改为 admin/blog 切换 Vditor + 方言 v2，见上条）；桌面端迁移待办
- **边界**：Wails 的采纳理由是 UI / 类型复用与方言引擎收敛；Vditor POC 暴露的方言冲突最终通过 v2 白名单解决（见上条），不推翻 Wails

### 2026-10-25 · blog 构建产物并入后端 jar（方案 C 收敛）

- **形态**：仍保留「后端组装壳 + 数据化主题」，runtime 由 `frontend/apps/blog` 用 Vite 构建；Maven `prepare-package` 把 `dist` 拷入 jar 的 `static/blog/`，由后端同源提供 `/blog/**`
- **原因**：方案 A 的「壳 + 独立产物 + 双启动路径」把部署与排错复杂度叠加了；当前规模不需要独立前端发布/CDN，单产物更适合单维护者与 agent 调试
- **收益**：① 一个发布单元、一个 origin、一条启动路径；② 无 `BLOG_ASSET_BASE`、无跨域 CSP、无发布顺序与版本漂移；③ preview / E2E 直接打 8080
- **代价**：前端改动必须重打 jar；`mvn package` 依赖先执行 `pnpm build:blog`（缺失产物直接失败；`mvn test` 不依赖 dist）
- **保留**：`GET /api/blog/bootstrap` 降级为调试 / agent / 未来桌面端接口；`packages/theme-starter` 仍是默认主题唯一源，后端兜底副本由 `pnpm sync:theme` 同步

### 2026-10-25 · 博客 runtime 改为独立构建产物（方案 A，已被同日方案 C 收敛）

- **形态**：仍保留「后端组装壳 + 数据化主题」，但 `runtime.js` 由 `frontend/apps/blog` 用 Vite 构建后独立部署（同域 `/assets/blog/<release>/` 或 CDN），后端不再托管前端产物
- **启动**：生产由后端壳注入 site + 主题；开发 / 无壳路径调用新增的 `GET /api/blog/bootstrap`（带 ETag）
- **收益**：① blog 前端恢复可构建、可类型检查、可独立发版；② 直接复用 `packages/shared` 的 Markdown 方言与 hljs，消除一分为二的等价实现；③ 后端 jar 不再背前端资产
- **代价**：两个发布产物需要版本纪律（先发 runtime，再切 `BLOG_ASSET_BASE` / 后端）；壳缓存需要按主题 + 配置版本失效（`s-maxage=60`，必要时 purge）
- **默认主题**：唯一源迁到 `frontend/packages/theme-starter/`，后端兜底副本由 `pnpm sync:theme` 同步、`pnpm check:theme` 校验

### 2026-10-24 · 博客前台改为「运行时 + 数据化主题」（详见 `docs/blog-runtime-theme.md`）

- **形态**：博客前台不再作为 Vue SPA 构建产物发布，改为后端组装页面骨架（注入站点配置 + 主题 HTML/CSS）+ 无构建 `runtime.js` 接管路由、取数、四态、暗色与 Markdown 渲染；主题由管理端以不可变版本存储，改主题不发版
- **渲染归属**：Markdown 渲染由 md-editor-v3 `MdPreview` 改为 runtime 内置 `markdown-it`（`breaks:false` / github-alerts / `html:false` 与 admin 等价，经 `docs/markdown-dialect-fixture.md` 双向验证）；md-editor-v3 仅用于 admin 编辑与预览
- **理由**：① 主题可替换且不破坏方言一致性；② 移动端只有一套 DOM，质量可验收；③ 主题作者只写 HTML/CSS，便于后续 agent 产出；④ 运行时随代码发布、可评审，主题存库、可回滚
- **代价**：引入契约版本（runtime/theme contract）与主题版本、预览 token、模板校验等机制；Vue 运行时模板编译需要 CSP `unsafe-eval`，靠 blog/admin 分域 + 零主题 JS 兜底
- **影响**：`frontend/apps/blog` 由 SPA 改为无构建运行时；`pnpm dev:blog` / `build:blog` 需相应调整；blog 端不再使用 `packages/api` 的生成类型，改由 runtime 内统一 API 封装收口（2026-10-25 已进一步改为 Vite 构建产物并并入后端 jar，见上两条）

### 2026-10-18 · admin 端实现落地（后端接 springdoc；Web 端 API 客户端生成链路收口）

- **后端 API 契约**：接入 `springdoc-openapi-starter-webmvc-api` 3.1.1（v3 线对应 Spring Boot 4；只含 JSON 端点，无 Swagger UI）；`/v3/api-docs` 匿名放行（`SecurityConfig`），`OpenApiConfig` 声明契约元信息与 Bearer 方案；响应 DTO（record）补 `@Schema(requiredProperties = ...)`，使契约中"恒存在的 key"为 required、可空字段保持可选（生成端 TS 类型更贴近实际）
- **API 客户端生成（待定 #5 的 Web 端收口）**：类型用 **openapi-typescript** 生成到 `frontend/packages/api/src/generated/schema.d.ts`（`pnpm gen:api`，默认拉 `http://localhost:8080/v3/api-docs`，可用 `OPENAPI_URL` 覆盖）；端点封装手写在 `packages/api/src/endpoints.ts`，传输层沿用手写轻量 fetch 封装——不引入 openapi-fetch / orval
- **admin 端落地**（设计依据 `docs/auth-rbac.md` §6.2）：服务端菜单驱动动态路由 + 页面注册表、`v-perm` 指令、401 清凭证回登录页 / 403 刷新 me 重建菜单、多标签页、暗色主题（跟随系统 + 手动覆盖）、移动端抽屉侧边栏；页面：登录、首页、用户管理、角色管理（含按端分 Tab 的权限树授权）、菜单管理、个人中心
- **样式优先级约定**：Element Plus 样式经 `@layer element-plus` 导入（层序 `theme, base, components, element-plus, utilities`）——避免 Tailwind Preflight 重置组件样式，同时保证工具类可覆盖组件样式（详见 `frontend/AGENTS.md`）
- **验证**：`pnpm typecheck` / `pnpm build:admin` 通过；浏览器端到端走查（登录 / 深链刷新 / 各页面 CRUD / 授权勾选与半选态 / v-perm 隐藏 / 403 自动刷新菜单 / 主题与页签）通过

### 2026-10-18 · RBAC 后端实现落地（详见 `docs/auth-rbac.md` §13）

- **范围**：`docs/auth-rbac.md` §10 的**后端落点全部完成**（JWT + 会话、授权快照缓存、`SessionJwtFilter`、路径域规则、方法级权限与超管短路、启动对账 fail-fast、`V1` 迁移六表 + 初始权限目录 + 预置超管），接口与 §8 契约一致；admin / blog / desktop 三端实现随后进行
- **工程化（待定 #6 部分收口）**：后端集成测试引入 **Testcontainers**（`spring-boot-testcontainers` + `testcontainers-postgresql`，`@ServiceConnection`，镜像 `postgres:17-alpine`）——测试不再连本地 / 远程开发库；并新增 `spring-boot-starter-validation`、Caffeine（BOM 管理）、`spring-security-oauth2-jose`（BOM 管理）
- **配置**：新增 `simple-notes.auth.jwt-secret: ${JWT_SECRET}`（32+ 字节，缺失 / 过短启动失败）；本地值来源模板 `application-local.yml.example` 已补 `JWT_SECRET` 条目
- **注意**：`V1__init_auth_rbac.sql` 已在测试环境执行，按"脚本只追加"纪律冻结，后续修正如需走新迁移

### 2026-10-18 · RBAC 设计评审修订（v1.1；详见 `docs/auth-rbac.md` §13）

- **认证传输**：取消 Cookie 与 CSRF 方案，三端统一 `Authorization: Bearer`（登录响应体统一返回 JWT；Web 凭证存 localStorage、按应用独立 key）——原 `sn_session` Cookie / 双提交 CSRF 作废；待定 #3 的 Cookie 作用域约束随之消解
- **超管**：root 用户 + `SUPER_ADMIN` 角色 + 绑定关系由初始迁移预置（固定 UUID；root 初始密码固定 `admin@123456`，首登应改密）；授权短路唯一依据 = 持有超管角色（`ROOT_USERNAME` / `ROOT_PASSWORD` 配置取消、`sys_user.builtin` 列取消）
- **会话回收**：禁用 / 删除 = 写路径撤销全部会话行；入口失效（停用 `entry:*` / 角色被收走 entry）= 请求链路校验并即时撤销会话
- **权限码**：新增 `admin:user:assign-role`（角色分配独立按钮 / 接口，含 `/api/admin/roles/assignable`）；`me` 的 `menus` 仅 ADMIN 端返回
- **实现约定**：授权快照先读版本再查库、菜单树不进快照、对账附带结构校验、V1 迁移口径（建表 + 初始节点 + 预置数据）、登录限流用户名 + IP 双维度、`sys_user_session` 增 `expire_time` 索引

### 2026-10-18 · 认证与权限体系设计定稿（RBAC；详见 `docs/auth-rbac.md`）

- **认证机制收口（待定 #1）**：**JWT + 服务端会话记录** —— JWT（HS256；claims = `sub` / `jti` / `iat` / `exp`，**不嵌权限**）+ `sys_user_session` 会话表（键 = `jti`，**存数据库而非纯内存**，重启不丢会话与撤销记录）；Web 走 HttpOnly Cookie（`sn_session`），桌面走 Bearer；授权用服务端快照缓存（全局版本失效 + 10 分钟 TTL 兜底），角色 / 权限变更**下一次请求即生效**，不做"权限变更踢线"
- **桌面端 token 存储（待定 #1 后半）**：不采用系统钥匙串（Linux 侧依赖 D-Bus / keyring、.NET 生态与 AOT 兼容不确定、防不住同用户态进程）——令牌存**本地 SQLite**（用户级文件权限；`ITokenStore` 抽象留平台加密扩展；认证数据永不参与内容同步 / 导出 / 备份）
- **RBAC 模型**：用户 → 多角色（权限并集）→ 权限码集合；三端入口（`entry:*`）+ 菜单（MENU = 页面可见 + 读接口）+ 按钮 / 接口（ACTION = 按钮显隐 + 写接口，同码）；"没有页面就是目录"
- **目录与对账**：目录存 `sys_menu`（迁移只 INSERT 节点，改名 / 调序 / 启停走 admin 端）；代码侧 `Perms` 常量 + **启动对账 fail-fast**；内置 root 用户 + `SUPER_ADMIN` 判定短路（不受权限限制）
- **admin 端加载**：服务端菜单列表驱动（`me` 返回已过滤菜单树）；前端页面注册表（权限码 → 路径 / 组件）按列表注册路由，无路由级权限判断
- **联动**：待定 #3 部署形态补注 Cookie 影响（admin 子域时 Cookie Domain / CSRF 需相应配置）

### 2026-10-18 · 后端机密配置策略（${} 占位符 + 外部值源）与对象存储选型 Cloudflare R2

- **变更**：
  - `application.properties` → `application.yml`（同一位置两种格式并存时 `.properties` 优先，故不能保留旧文件）
  - `application.yml` 中全部连接类配置改为 `${}` 占位符（`${DB_URL}` / `${DB_USERNAME}` / `${DB_PASSWORD}` / `${R2_*}`），只保留非机密默认值（URL、用户名、bucket 有默认，密码/密钥无默认）
  - 值来源：本地 `application-local.yml`（`.gitignore` 按路径忽略，模板 `application-local.yml.example` 入库）；部署用同名环境变量，无需改动配置或代码
- **选型**：对象存储定为 **Cloudflare R2**（S3 兼容）。配置键暂用 `simple-notes.storage.r2.*` 占位；若最终采用 spring-cloud-aws，则改用 `spring.cloud.aws.*` 标准键，随附件 / 图片功能设计定稿
- **实现注意（实测）**：
  - 占位符按名在 Environment 中解析，优先级：命令行参数 > 环境变量 > 配置文件（已实测环境变量覆盖文件值生效）
  - **占位符未提供值不会 fail-fast**：配置绑定（`@ConfigurationProperties`，`spring.datasource.*` 即此路径）会把字面量 `${DB_PASSWORD}` 原样传给 pgjdbc，表现为连接期认证失败；`@Value` 方式则会直接抛 `Could not resolve placeholder`（已验证）。需要快速失败须自行加显式校验
  - IDE / `mvn spring-boot:run` 下改动值文件即时生效；`java -jar` 的 classpath 取自构建产物，需重新 `package`，或改用 `--spring.config.additional-location=optional:file:...`（已验证）
  - 值文件位于 `src/main/resources`，本地 `mvn package` 会将其打进 fat jar；本地自用可接受，将来若要分发 jar / 推镜像需改为外部文件方案
- **后续可选加固**：gitleaks 之类的 pre-commit 机密扫描（见待定清单第 6 条）；如需配置缺失快速失败，可加显式启动校验

### 2026-10-18 · 后端依赖安全覆盖（CVE 告警收口）与 logback 已接受风险

- **背景**：IDE 依赖漏洞检查（Mend.io 数据源）在 `backend/pom.xml` 报出 8 条 CVE 告警，涉及版本全部来自 Spring Boot 4.1.1 BOM 管理（与 pom 改造无关，改造前后版本一致）
- **处置**：`dependencyManagement` 显式覆盖到已核实的修复版本。注意：BOM 导入方式下属性覆盖**不生效**（实测 `<tomcat.version>` 无效），必须显式声明条目：
  - `postgresql` 42.7.13 → **42.7.14**：CVE-2026-107314 / CVE-2026-107315（依据 pgjdbc `REL42.7.14` 发布说明，2026-10-07）
  - `tomcat-embed-core/el/websocket` 11.0.24 → **11.0.27**：11.0.25 修复 8 条、11.0.26 修复 CVE-2026-86350 / CVE-2026-77762（依据 tomcat.apache.org `security-11.html` 的 `Affects` / `Fixed in` 段落）
  - `jackson-databind` 3.1.5 → **3.1.7**：3.1.6 修复 CVE-2026-68497 / 83557 / 19032，3.1.7 修复 CVE-2026-91777 / 91776（依据 GitHub Advisory 的 Maven 版本范围）
  - `jackson-core` 3.1.5 → **3.1.7**：CVE-2026-89407 / 89425
  - `asm` 9.7.1 → **9.11**：6 条告警均为 "before 9.11"；仅测试域
- **已接受风险（不覆盖）**：`logback-classic` 1.5.38 的 CVE-2026-19880 修复只存在于 1.6.3+（后续 CVE-2026-104721 于 1.6.5 修复），1.5.x 线（当前最新 1.5.38）无修复版；跨 minor 升到 1.6.x 属 Spring Boot 4.1 未验证组合。漏洞点为 `MDCBasedDiscriminator` / `SiftingAppender`：仅在自定义 logback 配置且使用该 appender 时触发，本项目无 logback 配置（默认 `ConsoleAppender`），无实际暴露
- **移除条件**：`spring-boot-dependencies` 补丁版抬升到不低于上述覆盖版本后，删除覆盖块；logback 随 Boot 升级或 1.5.x 修复版发布复查（见待定清单）

### 2026-10-18 · 后端 pom 改用 BOM 导入（不再继承 starter-parent），修正 groupId / 包名

- **变更**：`backend/pom.xml` 不再继承 `spring-boot-starter-parent`，改为导入 `spring-boot-dependencies`（`scope=import`）；原先由 parent 提供的默认值——插件版本（spring-boot-maven-plugin 的 `repackage` 绑定、maven-compiler-plugin、maven-surefire-plugin）、编译器 `-parameters`、源码编码、`maven.compiler.release`——全部显式声明
- **理由**：parent 会连带注入资源过滤、assembly、native profile 等大量与本项目无关的配置，XML 继承链也不便核对实际生效值；BOM 只负责依赖版本管理，pom 自描述，后续若需换 parent 无迁移成本
- **代价 / 注意**：① pluginManagement 不随 BOM 导入，插件版本需自行维护（统一收在 `spring-boot.version` 等属性）；② 注解处理器路径（Lombok / QueryDSL APT）不解析 BOM 版本，须显式书写，Lombok 版本要与 BOM 内 `lombok.version` 对齐；③ `application*.properties/yml` 的 `@...@` 资源过滤不再默认启用（当前未使用），需要时自行补 maven-resources-plugin 配置
- **附带修正**：groupId `naihao.cc` → `cc.naihao`（域名倒置写反）；基础包 → `cc.naihao.simplenotes`；启动类 `BackendApplication` → `SimpleNotesApplication`（测试类同步）

### 2026-10-18 · 后端数据层引入 QueryDSL

- **变更**：后端在 Spring Data JPA 之上引入 QueryDSL（类型安全 / 动态条件查询）。依赖 `io.github.openfeign.querydsl:querydsl-jpa:7.7`（Apache-2.0），编译期由 `querydsl-apt`（classifier `jpa`）生成 Q 类型
- **理由**：官方 `com.querydsl` 坐标最后发版 5.1.0（2024-01，面向 Hibernate 6.x），未适配 Spring Boot 4.1 所用 Hibernate ORM 7；OpenFeign 维护分支为事实上游续作，7.7 对齐 Hibernate 7.4 / Jakarta Persistence 3.2，并保留 `com.querydsl` 包名与 API（Spring Data 的 `QuerydslPredicateExecutor` 可直接配合）
- **实现注意**：Java 23+（本项目 Java 25）须在 maven-compiler-plugin 显式 `<proc>full</proc>`，否则 Q 类型静默不生成；`querydsl.version` 属性名与 Spring Boot BOM 冲突，项目内改用 `openfeign-querydsl.version`
