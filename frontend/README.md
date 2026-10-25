# simple-notes · 前端工作区

pnpm workspace 管理的多前端仓库：**管理端（admin）** + **博客 runtime（blog）**，共享 API 客户端、工具函数、设计 token 与 Markdown 方言配置。

## 目录结构

```
frontend/
├── pnpm-workspace.yaml     # workspace 定义 + catalog 统一依赖版本
├── tsconfig.base.json      # 各子包 tsconfig 的公共基础
├── scripts/                # 内置默认主题同步 / 校验
├── apps/
│   ├── admin/              # 管理端（Vue 3 + Element Plus + Tailwind v4 + Vite）
│   └── blog/               # 博客 runtime（Vue 3 + Vite；产物 runtime.js 由后端壳引用）
└── packages/
    ├── api/                # @simple-notes/api    后端 API 客户端（管理端 + 桌面端复用）
    ├── shared/             # @simple-notes/shared 工具函数 / composables / Markdown 方言
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
| `pnpm build:admin` / `pnpm build:blog` | 构建单个应用；blog 产物在 Maven 打包时进入后端 jar |
| `pnpm sync:theme` / `pnpm check:theme` | 同步 / 校验后端内置默认主题副本 |
| `pnpm build` | 构建全部应用 |
| `pnpm gen:api` | 按后端 OpenAPI 契约重新生成 `packages/api` 类型（需后端已启动；默认 `http://localhost:8080/v3/api-docs`，可用 `OPENAPI_URL` 覆盖） |
| `pnpm typecheck` | 全仓类型检查 |

开发期 `/api` 请求由 Vite proxy 转发到后端 `http://localhost:8080`（见各应用 `vite.config.ts`）；需要连其他后端端口时用 `VITE_API_PROXY=http://localhost:8081 pnpm dev:admin` 覆盖。

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
- Markdown 方言、hljs、github-alerts 均来自 `@simple-notes/shared`，不在 app 内重复配置

## 已定选型（设计阶段记录）

| 位置 | 选型 | 说明 |
| --- | --- | --- |
| 管理端 Markdown 编辑器 | **md-editor-v3 7.x**（2026-09 活跃） | Vue 3.5 原生分屏编辑器：工具栏 / 预览 / 目录 / 图片上传 / KaTeX / Mermaid 内置，自带 XSS 清洗；peer 要求 Vue `^3.5.3`（当前 3.5.44 满足） |
| 博客前台 Markdown 渲染 | **runtime 内置 markdown-it**（2026-10-24 起；2026-10-25 改为构建产物） | 方言配置从 `@simple-notes/shared` 取（`breaks: false` / `html: false` / github-alerts / hljs 自托管），md-editor-v3 只服务 admin 编辑与预览；主题零 JS |

> 注意：md-editor-v3 的全局 `config()`（breaks、插件、hljs、主题）作用于模块级实例；blog runtime 使用同一份 `markdownDialect` 语义源构造 markdown-it，两端一致性由 fixture 双向验证。

## 约定

- **依赖版本**统一写在根 `pnpm-workspace.yaml` 的 `catalog`，子包用 `"xxx": "catalog:"` 引用
- `apps/*` 之间**禁止互相依赖**；共享代码一律下沉到 `packages/*`，用 `"workspace:*"` 引用（内部包直接导出 TS 源码，无需构建）
- **响应式**：以 `@simple-notes/shared` 的 `useBreakpoint()` 为全项目唯一的“移动端 / 桌面端”判断来源，禁止 UA 判断设备；路由与业务逻辑只保持一份，仅按断点分叉展示层（表格 vs 卡片等）
- **设计 token**：颜色、字体等共享 token 在 `packages/ui/src/tokens.css`（Tailwind v4 `@theme`），应用在 `src/styles/main.css` 中引入
- **API 契约**：后端 OpenAPI 是唯一来源（springdoc 导出 `/v3/api-docs`）。`packages/api` 的类型由 `pnpm gen:api`（openapi-typescript）生成到 `src/generated/schema.d.ts`，**禁止手写接口类型**；端点封装手写在 `src/endpoints.ts`，新增接口流程 = 后端改接口 → 重新 `gen:api` → 补封装
- **样式优先级**：Element Plus 样式必须经 CSS 层导入（`@layer element-plus`，层序 `theme, base, components, element-plus, utilities`）——否则 Tailwind Preflight 会重置组件样式；应用级覆盖写在 `styles/main.css` 末尾（未分层，优先级最高）
- **管理端请求**：一律走 `apps/admin/src/api` 的实例（自动注入 Bearer；401 清凭证回登录页、403 `PERMISSION_DENIED` 刷新 `me` 并重建菜单 / 路由）
