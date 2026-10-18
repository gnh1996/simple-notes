# 技术选型总览

> **冻结日期：2026-10-18**（第一阶段选型收口）
> 变更机制见文末。相关文档：`docs/auth-rbac.md`、`docs/markdown-dialect.md`、各端 README 选型表。

## 后端

| 项 | 结论 |
| --- | --- |
| 语言 / 框架 | Java 25 + Spring Boot 4.1.1（WebMVC） |
| 构建 | Maven（wrapper）；依赖版本走 `spring-boot-dependencies` BOM 导入，不继承 `spring-boot-starter-parent` |
| 坐标 / 包名 | groupId `cc.naihao`；基础包 `cc.naihao.simplenotes`，启动类 `SimpleNotesApplication` |
| 数据层 | PostgreSQL + JPA（QueryDSL 类型安全 / 动态查询）+ Flyway 迁移 |
| 对象存储 | Cloudflare R2（S3 兼容；上传 / 访问链路随附件功能设计定） |
| 本地配置 | 非机密在 `application.yml`；机密在 `application-local.yml`（不入库，模板入库）；部署用环境变量覆盖 |
| 安全 | Spring Security；认证机制已定：JWT + 服务端会话（三端统一 Bearer；`docs/auth-rbac.md`） |
| API 契约 | OpenAPI 导出 → 生成 Web(TS) / 桌面(C#) 客户端（工具随契约设计定） |

## Web（`frontend/`，pnpm workspace）

| 项 | 结论 |
| --- | --- |
| 基础栈 | Vue 3.5 + Vite 8 + TypeScript 6 + vue-router 5 + pinia 4 |
| 样式 | Tailwind CSS v4 + 共享设计 token（`packages/ui`）；Element Plus 样式按 CSS 层导入（见 `frontend/AGENTS.md`） |
| 管理端 UI | Element Plus 2.14 |
| API 客户端 | 后端 springdoc 导出 OpenAPI → `packages/api` 用 **openapi-typescript** 生成类型（`pnpm gen:api`）+ 自建轻量 fetch 封装（传输层与 Bearer 注入在应用侧） |
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

1. **认证机制**（已定，2026-10-18）：JWT + 服务端会话记录（存数据库）；桌面端令牌存本地 SQLite（不采用系统钥匙串）——详见 `docs/auth-rbac.md`
2. **附件 / 图片访问链路**：公开桶 + CDN vs 预签名 URL、直传 vs 服务端中转（存储后端已定 Cloudflare R2，随图片功能设计定）
3. **部署形态**：admin 挂 `/admin` 子路径 vs 子域（影响 vite `base` 与资源 / 代理配置；认证已不依赖 Cookie，无跨域 Cookie 约束）
4. **桌面本地存储实现**：Microsoft.Data.Sqlite 手写 SQL vs EF Core
5. **API 客户端生成工具**（Web 端已定，2026-10-18）：**openapi-typescript**（仅生成类型到 `packages/api/src/generated`，请求侧沿用 `packages/api` 的轻量 fetch 封装）；契约来自后端 springdoc 的 `/v3/api-docs`（`pnpm gen:api` 拉取生成）。C# 端 Kiota vs openapi-generator 待定
6. **工程化**：Vitest、ESLint/Prettier、后端 Testcontainers、机密防泄漏扫描（gitleaks 等）随实现补 —— **后端 Testcontainers 已补**（2026-10-18，随 RBAC 集成测试起用：`spring-boot-testcontainers` + `testcontainers-postgresql`，`@ServiceConnection` 注入）；其余待补
7. **桌面 Markdown 编辑库正式确认**（AvaloniaEdit 为默认候选）
8. **logback CVE-2026-19880 复查**：1.5.x 线无修复版，待 Spring Boot 升级至 logback 1.6.x 或 1.5.x 发布修复版后复核（详见变更记录）

## 变更机制

- "冻结"= 默认不再回头讨论，除非有新信息（许可证变化、兼容性问题、维护停更等）
- 变更流程：更新本文件 + 记录理由；相关细节文档（方言、README）同步更新
- 已发生的变更记录可追加到本文件文末

## 变更记录

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
