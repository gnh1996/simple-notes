# 技术选型总览

> **冻结日期：2026-10-18**（第一阶段选型收口）
> 变更机制见文末。相关文档：`docs/markdown-dialect.md`、各端 README 选型表。

## 后端

| 项 | 结论 |
| --- | --- |
| 语言 / 框架 | Java 25 + Spring Boot 4.1.1（WebMVC） |
| 构建 | Maven（wrapper）；依赖版本走 `spring-boot-dependencies` BOM 导入，不继承 `spring-boot-starter-parent` |
| 坐标 / 包名 | groupId `cc.naihao`；基础包 `cc.naihao.simplenotes`，启动类 `SimpleNotesApplication` |
| 数据层 | PostgreSQL + JPA（QueryDSL 类型安全 / 动态查询）+ Flyway 迁移 |
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
8. **logback CVE-2026-19880 复查**：1.5.x 线无修复版，待 Spring Boot 升级至 logback 1.6.x 或 1.5.x 发布修复版后复核（详见变更记录）

## 变更机制

- "冻结"= 默认不再回头讨论，除非有新信息（许可证变化、兼容性问题、维护停更等）
- 变更流程：更新本文件 + 记录理由；相关细节文档（方言、README）同步更新
- 已发生的变更记录可追加到本文件文末

## 变更记录

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
