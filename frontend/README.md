# simple-notes · 前端工作区

pnpm workspace 管理的多前端仓库：**管理端（admin）** + **博客 runtime（blog）** + **桌面端 UI（desktop）**，共享 API 客户端、工具函数、设计 token 与 Markdown 方言配置。

## 目录结构

```
frontend/
├── pnpm-workspace.yaml     # workspace 定义 + catalog 统一依赖版本
├── tsconfig.base.json      # 各子包 tsconfig 的公共基础
├── scripts/                # 内置默认主题同步 / 校验
├── apps/
│   ├── admin/              # 管理端（Vue 3 + Element Plus + Tailwind v4 + Vite）
│   ├── blog/               # 博客 runtime（Vue 3 + Vite；产物 runtime.js 由后端壳引用）
│   └── desktop/            # 桌面端 UI（Vue 3 + Vite；由 Wails WebView 加载，bindings/ 由 Go 生成）
└── packages/
    ├── api/                # @simple-notes/api    后端 API 客户端（管理端 + 桌面端复用）
    ├── markdown/           # @simple-notes/markdown  Vditor / Lute 配置、DOMPurify 清洗、博客渲染入口
    ├── shared/             # @simple-notes/shared 工具函数 / composables / 旧 md-editor-v3 配置（桌面端过渡期）
    ├── theme-starter/      # @simple-notes/theme-starter 内置默认主题（runtime 兜底 + admin starter）
    └── ui/                 # @simple-notes/ui     共享设计 token（Tailwind @theme）
```

## 常用命令

在 `frontend/` 目录下执行：

| 命令 | 说明 |
| --- | --- |
| `pnpm install` | 安装全部依赖 |
| `pnpm dev:admin` | 启动管理端 dev server（<http://localhost:5173>） |
| `pnpm dev:blog` | 启动博客 runtime dev server（<http://localhost:5174>；页面走后端 8080） |
| `pnpm dev:desktop` | 启动桌面端 UI dev server（<http://localhost:5175>；binding 调用需在 Wails 壳内） |
| `pnpm build:admin` / `pnpm build:blog` / `pnpm build:desktop` | 构建单个应用；blog 产物在 Maven 打包时进入后端 jar；desktop 产物由 `desktop/` 的 `wails3 build` 同步并嵌入 |
| `pnpm sync:theme` / `pnpm check:theme` | 同步 / 校验后端内置默认主题副本 |
| `pnpm check:contract` | 校验博客主题契约（runtime 暴露面 == `contract.ts` 登记面） |
| `pnpm build` | 构建全部应用 |
| `pnpm gen:api` | 按后端 OpenAPI 契约重新生成 `packages/api` 类型（需后端已启动；默认 `http://localhost:8080/v3/api-docs`，可用 `OPENAPI_URL` 覆盖） |
| `pnpm typecheck` | 全仓类型检查 |

开发期 `/api` 请求由 Vite proxy 转发到后端 `http://localhost:8080`（见 admin / blog 的 `vite.config.ts`；桌面端不走 proxy，由 Go binding 转发）；需要连其他后端端口时用 `VITE_API_PROXY=http://localhost:8081 pnpm dev:admin` 覆盖。

## 管理端（apps/admin）结构

```
apps/admin/src/
├── api/              # API 实例：Bearer 注入 + 401 / 403 统一处理
├── layouts/          # 布局：侧边栏（服务端菜单树）/ 顶栏（面包屑 + 用户菜单）/ 多标签页
├── router/           # 基础路由 + 守卫；page-registry.ts = 页面注册表（权限码 → 路径 / 组件 / 图标）
├── stores/           # auth（凭证 / me / 菜单树）、tabs（多标签页）
├── directives/       # v-perm（无码移除 DOM；超管放行）
├── composables/      # useTheme（暗色主题）
└── views/            # login / dashboard / system（user、role、menu）/ notes / posts / tags / media / trash / profile / fallback
```

- 侧边栏与路由由**服务端菜单树驱动**：`me.menus`（已按权限过滤）× 页面注册表 → `addRoute`；未命中注册表按目录递归，前端不做路由级权限判断（`docs/auth-rbac.md` §6.2）
- 权限码只用于按钮显隐（`v-perm`）与菜单注册；接口权限后端终审

## 博客 runtime（apps/blog）

- 只负责「运行时」：路由、取数、四态、Markdown、暗色、组件词汇表；版式由数据库下发的主题 HTML/CSS 决定（零 JS）
- 构建产物在 Maven `prepare-package` 时拷入后端 jar 的 `static/blog/`，由后端同源提供；页面与 API 都在 8080，单一启动路径
- 开发期：`mvn spring-boot:run` 默认启用 `dev` profile，`asset-base` 自动指向 Vite（`http://localhost:5174`）获得 HMR；生产 jar 不启用该 profile，固定从 jar 内 `/blog` 读取
- 内置默认主题在 `packages/theme-starter`；后端兜底副本用 `pnpm sync:theme` 同步、`pnpm check:theme` 校验
- Markdown 方言、Lute 预加载与 DOMPurify 清洗来自 `@simple-notes/markdown`；hljs / KaTeX / Mermaid 由 `pnpm sync:vditor` 自托管，不在 app 内重复配置

## 桌面端 UI（apps/desktop）

- 只负责渲染与交互；窗口、本地 SQLite、HTTP 传输、令牌都在 `desktop/` 的 Go 侧
- **不直连后端**：调用 Go binding `api.Do({ method, path, body })`；Bearer 由 Go 注入，token 不进入 JS
- `bindings/` 由 `wails3 task bindings` 生成（改 Go service 签名后重跑），随仓库提交；**禁止手写 IPC 类型**
- API 类型仍来自 `@simple-notes/api` 的 OpenAPI 生成物；Go 侧只做通用转发
- Markdown 过渡期使用 `configureMarkdown()` + `packages/shared`（md-editor-v3 / markdown-it）；与 admin 的 Vditor + Lute **尚未同源**，迁移前不参与方言 v2 验收。编辑器高度按容器计算，不做移动端分叉
- 构建产物由 `pnpm build:desktop` → `pnpm sync:wails` 同步到 `desktop/web/dist`（`//go:embed` 不能跨 module）

## 已定选型（设计阶段记录）

| 位置 | 选型 | 说明 |
| --- | --- | --- |
| 管理端 / 桌面端 Markdown 编辑器 | **Vditor 4.0.1**（Lute 内核；admin 已切换，desktop 待迁移） | IR / WYSIWYG，KaTeX / Mermaid / emoji / 表格内置；后端只存原文 |
| 博客前台 Markdown 渲染 | **Lute**（`@simple-notes/markdown` + `method.min.js`，2026-10-25 起） | 与 admin 共用 `vditorMarkdown`；DOMPurify 清洗；Math / Mermaid / hljs 按需加载 |

> 注意：admin 与 blog 共用 `packages/markdown` 的 `vditorMarkdown` 与 `sanitizeHtml`；主题 CSS 需按 Lute 的 `.callout` / KaTeX / 任务列表结构适配。Vditor 静态资源由 `pnpm sync:vditor`（各应用 predev/prebuild 自动执行）裁剪复制到 `public/vditor`，禁止公共 CDN。

## 约定

- **依赖版本**统一写在根 `pnpm-workspace.yaml` 的 `catalog`，子包用 `"xxx": "catalog:"` 引用
- `apps/*` 之间**禁止互相依赖**；共享代码一律下沉到 `packages/*`，用 `"workspace:*"` 引用（内部包直接导出 TS 源码，无需构建）
- **响应式**：以 `@simple-notes/shared` 的 `useBreakpoint()` 为全项目唯一的“移动端 / 桌面端”判断来源，禁止 UA 判断设备；路由与业务逻辑只保持一份，仅按断点分叉展示层（表格 vs 卡片等）
- **设计 token**：颜色、字体等共享 token 在 `packages/ui/src/tokens.css`（Tailwind v4 `@theme`），应用在 `src/styles/main.css` 中引入
- **API 契约**：后端 OpenAPI 是唯一来源（springdoc 导出 `/v3/api-docs`）。`packages/api` 的类型由 `pnpm gen:api`（openapi-typescript）生成到 `src/generated/schema.d.ts`，**禁止手写接口类型**；端点封装手写在 `src/endpoints.ts`，新增接口流程 = 后端改接口 → 重新 `gen:api` → 补封装
- **样式优先级**：Element Plus 样式必须经 CSS 层导入（`@layer element-plus`，层序 `theme, base, components, element-plus, utilities`）——否则 Tailwind Preflight 会重置组件样式；应用级覆盖写在 `styles/main.css` 末尾（未分层，优先级最高）
- **管理端请求**：一律走 `apps/admin/src/api` 的实例（自动注入 Bearer；401 清凭证回登录页、403 `PERMISSION_DENIED` 刷新 `me` 并重建菜单 / 路由）
