# 技术选型总览

> **冻结日期：2026-10-18**（第一阶段选型收口）。本文只记结论；为什么这么选、换过什么，见 git log 与各文档内的理由段。
> 相关文档：`docs/content-model.md`、`docs/auth-rbac.md`、`docs/markdown-dialect.md`、各端 README 选型表。

## 后端

| 项 | 结论 |
| --- | --- |
| 语言 / 框架 | Java 25 + Spring Boot 4.1.1（WebMVC） |
| 构建 | Maven（wrapper）；依赖版本走 `spring-boot-dependencies` BOM 导入，不继承 `spring-boot-starter-parent` |
| 坐标 / 包名 | groupId `cc.naihao`；基础包 `cc.naihao.simplenotes`，启动类 `SimpleNotesApplication` |
| 数据层 | PostgreSQL + JPA（QueryDSL 类型安全 / 动态查询）+ Flyway 迁移 |
| 对象存储 | Cloudflare R2（S3 兼容）；私有 bucket + 媒体域 Nginx `auth_request` 预签名代理（见 `docs/attachment-storage.md`） |
| 本地配置 | 非机密在 `application.yml`；机密在 `application-local.yml`（不入库，模板入库）；部署用环境变量覆盖 |
| 安全 | Spring Security；JWT + 服务端会话（三端统一 Bearer；见 `docs/auth-rbac.md`） |
| API 契约 | 后端 springdoc 导出 OpenAPI 为单一来源；Web / 桌面端只用生成类型 |
| AI | Spring AI 2.0 + OpenAI 兼容接口；缺 key 时 fail-soft 启动（见 `docs/theme-ai.md`） |

## Web（`frontend/`，pnpm workspace）

| 项 | 结论 |
| --- | --- |
| 基础栈 | Vue 3.5 + Vite 8 + TypeScript 6 + vue-router 5 + pinia 4 |
| 样式 | Tailwind CSS v4 + 共享设计 token（`packages/ui`）；Element Plus 样式按 CSS 层导入（见 `frontend/AGENTS.md`） |
| 管理端 UI | Element Plus 2.14 |
| API 客户端 | `openapi-typescript` 生成类型（`pnpm gen:api`）+ `packages/api` 轻量 fetch 封装 |
| Markdown 编辑器 | Vditor 4.0.1（Lute 内核；admin / desktop 同源） |
| Markdown 渲染 | blog runtime 使用 Lute（`@simple-notes/markdown` + `method.min.js`），与 admin 共用 `vditorMarkdown` |
| 响应式 | `useBreakpoint()` 单一断点来源，只在展示层分叉 |

## 桌面端（`desktop/` + `frontend/apps/desktop`）

| 项 | 结论 |
| --- | --- |
| 基础栈 | Go 1.26+ + Wails v3.1.0（WebView2 / WKWebView / GTK4 + WebKitGTK 6.0） |
| UI | Vue 3.5 + Vite 8 + TypeScript 6（pnpm workspace 成员，复用 `packages/*`） |
| 架构 | Wails services / bindings；Go 侧负责窗口、HTTP 传输、SQLite、原生能力；WebView 不直连后端 |
| 数据模式 | 本地优先（SQLite + `modernc.org/sqlite`，纯 Go；离线可用 + 后台同步） |
| Markdown | Vditor 4.0.1 + Lute，与 admin / blog 同源；资源自托管 `/vditor` |
| 分发 | 三平台各自构建（Wails Taskfile / CI 矩阵），不使用 NativeAOT 单文件 |
| 内存基线 | 以 `desktop/README.md` 的实测表为唯一数据源，本文不复制数字 |

## 跨端

| 项 | 结论 |
| --- | --- |
| 内容方言 | v2：Lute / Vditor；允许数学、emoji、脚注、上下标、mark、安全行内 HTML（见 `docs/markdown-dialect.md`） |
| API 契约 | OpenAPI 单一来源，全端使用生成类型（桌面端经 Go binding 转发） |
| 权限 | 权限码 + `sys_menu` 启动对账（见 `docs/auth-rbac.md`） |

## 未定 / 待观察

1. **工程化补全**：Vitest、ESLint/Prettier、机密扫描（gitleaks 等）随实现补；后端 Testcontainers 已接入。
2. **logback CVE-2026-19880**：1.5.x 线无修复版（本项目未自定义 appender，已接受风险）；待 Boot 升级到 logback 1.6.x 或 1.5.x 出修复版后复查。
3. **博客中文搜索**：当前 `ILIKE '%keyword%'`；数据量上来后再评估 `pg_trgm`，不引入 `zhparser`。

## 变更机制

- "冻结" = 默认不再回头讨论；只有新信息（许可证、兼容性、维护停更）才重开。
- 改结论 = 改本表 + 在 commit body 写理由，并同步受影响文档；不再单独维护变更记录。
