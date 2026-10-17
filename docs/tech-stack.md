# 技术选型总览

> **冻结日期：2026-10-18**（第一阶段选型收口）
> 变更机制见文末。相关文档：`docs/markdown-dialect.md`、各端 README 选型表。

## 后端

| 项 | 结论 |
| --- | --- |
| 语言 / 框架 | Java 25 + Spring Boot 4.1.1（WebMVC） |
| 构建 | Maven（wrapper） |
| 数据层 | PostgreSQL + JPA + Flyway 迁移 |
| 安全 | Spring Security（认证机制属设计范畴） |
| API 契约 | OpenAPI 导出 → 生成 Web(TS) / 桌面(C#) 客户端（工具随契约设计定） |

## Web（`frontend/`，pnpm workspace）

| 项 | 结论 |
| --- | --- |
| 基础栈 | Vue 3.5 + Vite 8 + TypeScript 6 + vue-router 5 + pinia 4 |
| 样式 | Tailwind CSS v4 + 共享设计 token（`packages/ui`） |
| 管理端 UI | Element Plus 2.14 |
| Markdown 编辑器 | md-editor-v3 7（管理端） |
| Markdown 渲染 | 博客前台复用 md-editor-v3 `MdPreview`（与编辑器同源；备选自建 shiki 管线未采用） |
| 响应式 | `useBreakpoint()` 单一断点来源，展示层分叉 |

## 桌面端（`desktop/`）

| 项 | 结论 |
| --- | --- |
| 基础栈 | .NET 10 + Avalonia 12.1 |
| 架构 | CommunityToolkit.Mvvm + Microsoft.Extensions.DependencyInjection |
| 数据模式 | 本地优先（SQLite；离线可用 + 后台同步） |
| Markdown 渲染 | LiveMarkdown.Avalonia 2.4.3（Apache-2.0） |
| Markdown 编辑 | AvaloniaEdit 12（含 TextMate 高亮；待正式确认） |
| 分发 | 预留 NativeAOT 单文件发布 |

## 跨端

| 项 | 结论 |
| --- | --- |
| 内容方言 | v1 已定稿（`docs/markdown-dialect.md` + 验收 fixture） |
| API 契约 | OpenAPI 单一来源，全端生成客户端 |

## 待定清单（选型性质，随设计 / 实现推进确定）

1. **认证机制**：Session/Cookie vs JWT；桌面端 token 存储（libsecret / DPAPI / Keychain）
2. **附件 / 图片存储**：本地磁盘 vs MinIO / OSS（随图片功能设计定）
3. **部署形态**：admin 挂 `/admin` 子路径 vs 子域（影响 vite `base`）
4. **桌面本地存储实现**：Microsoft.Data.Sqlite 手写 SQL vs EF Core
5. **API 客户端生成工具**：Web 端 openapi-typescript/openapi-fetch vs orval；C# 端 Kiota vs openapi-generator
6. **工程化**：Vitest、ESLint/Prettier、后端 Testcontainers 等（随实现补）
7. **桌面 Markdown 编辑库正式确认**（AvaloniaEdit 为默认候选）

## 变更机制

- "冻结"= 默认不再回头讨论，除非有新信息（许可证变化、兼容性问题、维护停更等）
- 变更流程：更新本文件 + 记录理由；相关细节文档（方言、README）同步更新
- 已发生的变更记录可追加到本文件文末
