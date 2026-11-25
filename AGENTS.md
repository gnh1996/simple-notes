# simple-notes 项目规则

随手记应用兼个人博客：`backend`（Spring Boot 服务端）+ `frontend`（Vue3 Web 工作区：管理端 + 博客前台 + 桌面端 UI）+ `desktop`（Go + Wails v3 桌面壳）。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| `docs/tech-stack.md` | 技术选型总览（2026-10-18 冻结）与残余待定项 |
| `docs/auth-rbac.md` | 认证与权限设计（RBAC：三端入口 / admin 菜单 / 按钮与接口权限；JWT 会话；已定稿） |
| `docs/content-model.md` | 内容模型设计（随手记 / 博客单表、发布快照、标签管理、权限与 API；Web 端已实现） |
| `docs/attachment-storage.md` | 附件存储设计（本地 / R2 / 数据库三介质；任意文件、20MB、行级存储模式、DIRECT 外链、媒体域、私有 R2 + Nginx 预签名代理；已实现） |
| `docs/markdown-dialect.md` | Markdown 内容方言规范（v2 白名单 / 边界 / 三端配置对齐） |
| `docs/markdown-dialect-fixture.md` | 方言验收样本：新增语法必须 admin / blog / 桌面三端渲染比对 |
| `docs/blog-runtime-theme.md` | 博客运行时与主题化设计（已实现）：运行时/主题职责、契约表、主题版本与预览、站点配置与独立页面 |
| `docs/theme-contract.md` | 主题契约规范（规范性文档，P0 + P1 已实现）：能力面 + 表达式面、skeleton / example、fixture、威胁模型、变更协议 |
| `docs/theme-contract.generated.md` | 主题契约生成物：组件 / 类型 / 路由 / 变量 / 指令 / 表达式清单；禁止手改 |
| `docs/theme-contract-v2.md` | 主题契约 v2 历史设计（已冻结归档，不再更新；规范以 `theme-contract.md` 为准，漂移教训见 `theme-contract.md` 附录 A） |
| `docs/theme-ai.md` | 主题 AI 对话（补丁协议、契约投影、校验修复；已实现） |
| `docs/desktop-sync.md` | 桌面端同步设计（M0–M4 已实现）：本地优先 + 懒加载 manifest、`version` 修订号与本地 hash、整篇冲突 + diff、离线新建、账号隔离 |
| `docs/desktop-local-schema.md` | 桌面端本地库结构演进（SQLite schema 迁移，S1 / S2 已实现、S3 协议握手待触发）：`user_version` 版本轴、有序迁移与老库桥接、备份与降级检测、与后端 API 契约版本的关系 |
| `docs/desktop-local-file.md` | 桌面端本地 Markdown（临时编辑 / 多标签 / 文件夹树已实现，Windows）：打开文件=临时编辑、免登录本地笔记、最近打开、纳入管理、导出 Markdown；多标签 / 独立未保存 / 标签恢复；本地与云端文件夹两棵独立树；术语统一为「云端」 |
| `docs/desktop-media.md` | 桌面端图片（已实现，Windows）：文件 / 本地 / 云端三种插图、`<stem>.assets`、本地媒体库、`/__media` 导入与预览、媒体上传 `/api/desktop/media`、导出 / 同步改写 |
| `docs/desktop-layout.md` | 桌面端布局与文档工作台（已实现，Windows）：默认全宽编辑 + 可收起推挤式资料库、统一文档多标签、`Ctrl+K` 快速打开、启动恢复与本地优先 |
| `docs/desktop-diagnostics.md` | 桌面端错误处理与运行日志（已实现）：错误分层、网络失败降级、详情弹层、结构化日志 / 轮转 / 脱敏、诊断入口 |
| `docs/tech-eval-vditor-wails.md` | 可行性评估论证存档（不再更新；结论快照以 `tech-stack.md` 为准）：Wails v3 已采纳；三端 Vditor + Lute（v2） |
| `docs/deployment.md` | 部署（Docker 一键 / 拆分）：单镜像组成、数据目录、备份恢复、外部数据库、TLS 与边界 |
| `docs/deployment-selfhosted.md` | 自用双机部署（香港 nginx 静态 + 反代 / 腾讯云 app+db / WireGuard）：首次初始化、发布迭代、备份回滚与资源预算 |
| `docs/release.md` | 发版与版本确定：git tag 唯一来源、构建注入、发版流程与校验 |
| 各端 `README.md` | 目录结构、命令与端内细节（命令以此为准，`docs/` 不复述参数） |
| 各端 `AGENTS.md` | 端内专属规则（探索到对应目录时自动加载） |

> 文档分工：`docs/` 存为什么（决策 + 数据），`AGENTS.md` 存不许什么（禁则 + 文件指针），`README` 存怎么做（命令 + 结构）；命令细节与实测数字以各端 README 为准，`docs/` 与 `AGENTS.md` 只引用、不拷贝。
> 索引只写"这篇回答什么 + 状态（已实现 / 草案 / 归档）"，**不写版本号**；文档版本只出现在各文档 header。变更记录只在**能力 / 决策 / 事实更正**时追加；纯格式（对齐、空行、错字）不 bump、不写行，术语政策与权威来源变更属于决策变化，仍记一行。
> `pnpm check:docs` 校验文档索引、`docs/*.md` 的 `§` 交叉引用与章节存在性、表格列数、章节号唯一性、索引行无版本号，以及内容域权限码与 `Perms.java` 一致（`scripts/check-docs.mjs`），已并入 `pnpm typecheck`；新增文档必须先进本索引表。

## 当前阶段：设计先行

- 功能设计文档定稿后才进入实现；涉及未设计领域的改动，先补设计（写文档）或征询，不要直接写实现
- 已冻结的选型（见 `docs/tech-stack.md`）默认不再回头讨论；若有新信息推翻结论，按"变更决策"处理：更新对应文档并记录理由
- 决策记录位置：跨端 / 阶段性决策 → `docs/`；端内选型 → 各端 README 的选型表

## 阶段策略：预发布快速迭代

当前未上线、处于快速迭代阶段，**接口与数据结构允许破坏性调整**，不承担兼容旧客户端的成本。但以下纪律不变：

- **数据库迁移：先查历史，再决定改还是追加**
  - 改动前先查目标库的 `flyway_schema_history`；
  - 该版本尚未在任何"需要保留数据"的库执行 → 可以修改；
  - 已在任一库执行 → **只能追加更高版本**，不得修改 / 删除历史脚本；
  - **"开发环境"不是例外**：只要库里有需要保留的数据（包括远程开发库），一律按已执行处理。
- 确需重建开发库时，先导出要保留的数据、确认该库非共享，再重建；禁止对共享 / 生产库执行 `flyway clean` 或等价操作。
- 安全与一致性底线不放宽：启动对账 fail-fast、权限码一致、密钥不落库 / 日志、Markdown 方言 fixture、typecheck / 编译 / 关键单测。
- **退出条件**（任一满足即冻结，完全回到"设计先行 + 迁移只追加"）：
  1. 部署到生产环境；
  2. 数据库出现不可再生的真实内容；
  3. 打第一个正式版本 tag。

## 跨端关键约定

- **响应式（Web）**：`useBreakpoint()`（`@simple-notes/shared`）是全项目唯一的断点来源，禁止 UA / 设备类型判断；同一路由与逻辑只有一份，只在展示层按断点分叉
- **Markdown**：遵守 `docs/markdown-dialect.md` v2 白名单；admin / blog / desktop 共用 `@simple-notes/markdown` 的 `vditorMarkdown` / `sanitizeHtml`，禁止各端另写配置；新增语法必须先在 fixture 中完成三端验收
- **API 契约**：后端 OpenAPI 为单一来源，Web / 桌面端一律使用生成类型，禁止手写接口类型；桌面端经 Go binding 转发（WebView 不直连后端）
- **数据**：后端 PostgreSQL + Flyway（迁移只追加、不改历史；已在任一环境执行过的脚本不可修改 / 删除，回退只能新增更高版本补偿）；桌面端本地优先（离线可用 + 后台同步）
- **内容模型**：随手记与博客文章共用 `note` 表（`kind` 区分）；只有 `POST` 可发布，公开博客只读发布快照（`note_revision`），编辑后必须“更新发布”；不支持文章转回笔记（见 `docs/content-model.md`）
- **主题契约**：规范性规则见 `docs/theme-contract.md`，机器源是 `frontend/apps/blog/src/contract.ts`；主题只能使用登记过的能力与表达式子集，未登记即非法；新增 / 变更能力必须走四件套（契约 + fixture + 规范表 + `sync:contract` / 测试），默认主题不是规范
- **权限**：权限码是前后端唯一纽带——目录在 `sys_menu`（迁移只 INSERT、启动对账 fail-fast）、代码引用 `Perms` 常量；前端按服务端菜单列表注册路由、仅做显隐，接口权限后端终审（见 `docs/auth-rbac.md`）
- **术语**：桌面端「云端」= 笔记归属 / UI 来源（页签、徽标、状态文案）；「服务端」= 后端接口 / 行为（`/api/**`、5xx、同步、权限）。其余端不涉及「云端」概念，统一用「服务端」

## 常用命令速查

```bash
# Web（frontend/ 下执行）
pnpm install && pnpm typecheck
pnpm dev:admin            # http://localhost:5173
pnpm dev:blog             # http://localhost:5174（runtime dev；页面走后端 8080）
pnpm build:admin && pnpm build:blog
pnpm sync:theme           # 默认主题：packages/theme-starter → 后端兜底副本

# 桌面（desktop/ 下执行；UI 在 frontend/apps/desktop）
wails3 build              # bindings → 前端构建 → 同步 → 编译（需要先装 wails3 v3.1.0）
wails3 dev                # 需要图形环境
go test ./...

# 后端（backend/ 下执行；打包前先 pnpm build:blog，产物会进 jar）
./mvnw test
./mvnw spring-boot:run   # 默认 dev profile：asset-base → Vite 5174（HMR）

# Docker（仓库根：3 容器 db + app + web）
bash scripts/init-env.sh
bash scripts/docker-build.sh all   # 受限网络自动处理代理
docker compose up -d --no-build
```

## 禁则（Guardrails）

- 桌面端 WebView 只加载本地内嵌资源，API 一律走 Go binding；令牌只存 SQLite，不进入 JS；附件 / 大载荷不走 IPC
- 桌面端内存按 WebView 进程树预算评估（唯一数据源见 `desktop/README.md`，其余文档只引用不拷贝数字），新增常驻能力前先实测
- 桌面端 Go 侧保持无头可测；避免引入需要 CGO 的依赖（SQLite 用纯 Go 驱动）
- 不引入需要商业许可的组件；新增依赖前确认许可证可自由使用
- 前端依赖版本一律走根 `pnpm-workspace.yaml` 的 `catalog`；`apps/*` 之间禁止互相依赖
- 方言以 `docs/markdown-dialect.md` v2 白名单为准：允许数学、emoji、脚注、上下标、mark 与安全行内 HTML（渲染前必须清洗）；仍不收 front matter / 图片尺寸语法 / 服务端渲染

## 规则文件自身

- 本文件与 `docs/`、各端 `AGENTS.md` 随仓库提交，保证任何会话 / 工具拿到同一份约束
- 新增端内规则时写入对应目录的 `AGENTS.md`（按目录就近加载），避免膨胀本文件
