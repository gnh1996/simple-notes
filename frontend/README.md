# simple-notes · 前端工作区

pnpm workspace 管理的多前端仓库：**管理端（admin）** + **前台博客（blog）**，共享 API 客户端、工具函数与设计 token。

## 目录结构

```
frontend/
├── pnpm-workspace.yaml     # workspace 定义 + catalog 统一依赖版本
├── tsconfig.base.json      # 各子包 tsconfig 的公共基础
├── apps/
│   ├── admin/              # 管理端（Vue 3 + Element Plus + Tailwind v4 + Vite）
│   └── blog/               # 前台博客（Vue 3 + Tailwind v4 + Vite）
└── packages/
    ├── api/                # @simple-notes/api    后端 API 客户端（两端 + 桌面端复用）
    ├── shared/             # @simple-notes/shared 工具函数 / composables（含 useBreakpoint）
    └── ui/                 # @simple-notes/ui     共享设计 token（Tailwind @theme）
```

## 常用命令

在 `frontend/` 目录下执行：

| 命令 | 说明 |
| --- | --- |
| `pnpm install` | 安装全部依赖 |
| `pnpm dev:admin` | 启动管理端 dev server（<http://localhost:5173>） |
| `pnpm dev:blog` | 启动博客 dev server（<http://localhost:5174>） |
| `pnpm build:admin` / `pnpm build:blog` | 构建单个应用 |
| `pnpm build` | 构建全部应用 |
| `pnpm typecheck` | 全仓类型检查 |

开发期 `/api` 请求由 Vite proxy 转发到后端 `http://localhost:8080`（见各应用 `vite.config.ts`）。

## 已定选型（设计阶段记录）

| 位置 | 选型 | 说明 |
| --- | --- | --- |
| 管理端 Markdown 编辑器 | **md-editor-v3 7.x**（2026-09 活跃） | Vue 3.5 原生分屏编辑器：工具栏 / 预览 / 目录 / 图片上传 / KaTeX / Mermaid 内置，自带 XSS 清洗；peer 要求 Vue `^3.5.3`（当前 3.5.44 满足） |
| 博客前台 Markdown 渲染 | **复用 md-editor-v3 `MdPreview`**（定于 2026-10-18） | 与编辑器同源同管线，编辑器预览 = 发布所得；备选自建 `markdown-it + shiki` 管线未采用（一致性优先、零开发）。对齐事项：追加 `markdown-it-github-alerts` 插件、禁用内置 `!!!` admonition、`breaks: false`、hljs 自托管（生产不依赖 CDN）、关闭 `noKatex` / `noEcharts` |

> 注意：md-editor-v3 的全局 `config()`（breaks、插件、hljs、主题）作用于模块级实例，**admin 与 blog 是两个独立应用，需保证两端配置完全一致**（实现时抽取共享配置模块），否则「编辑器预览 = 发布所得」不成立。

## 约定

- **依赖版本**统一写在根 `pnpm-workspace.yaml` 的 `catalog`，子包用 `"xxx": "catalog:"` 引用
- `apps/*` 之间**禁止互相依赖**；共享代码一律下沉到 `packages/*`，用 `"workspace:*"` 引用（内部包直接导出 TS 源码，无需构建）
- **响应式**：以 `@simple-notes/shared` 的 `useBreakpoint()` 为全项目唯一的“移动端 / 桌面端”判断来源，禁止 UA 判断设备；路由与业务逻辑只保持一份，仅按断点分叉展示层（表格 vs 卡片等）
- **设计 token**：颜色、字体等共享 token 在 `packages/ui/src/tokens.css`（Tailwind v4 `@theme`），应用在 `src/styles/main.css` 中引入
- **API 契约**：后端（Spring Boot）接入 springdoc-openapi 后，生成 `packages/api` 的类型化客户端，供 admin / blog / 桌面端（C# 生成器同理）复用
