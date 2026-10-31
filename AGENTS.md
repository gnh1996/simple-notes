# simple-notes 项目规则

随手记应用兼个人博客：`backend`（Spring Boot 服务端）+ `frontend`（Vue3 Web 工作区：管理端 + 博客前台 + 桌面端 UI）+ `desktop`（Go + Wails v3 桌面壳）。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| `docs/tech-stack.md` | 技术选型总览（2026-10-18 冻结）与残余待定项 |
| `docs/auth-rbac.md` | 认证与权限设计（RBAC：三端入口 / admin 菜单 / 按钮与接口权限；JWT 会话；v1 定稿） |
| `docs/content-model.md` | 内容模型设计（随手记 / 博客单表、发布快照、标签管理、权限与 API；v1.4.3 Web 端已实现） |
| `docs/attachment-storage.md` | 附件存储设计（本地 / R2 / 数据库三介质；任意文件、20MB、行级存储模式、DIRECT 外链、媒体域、私有 R2 + Nginx 预签名代理；v1.2 已实现） |
| `docs/markdown-dialect.md` | Markdown 内容方言规范（v2 白名单 / 边界 / 三端配置对齐） |
| `docs/markdown-dialect-fixture.md` | 方言验收样本：新增语法必须 admin / blog / 桌面三端渲染比对 |
| `docs/blog-runtime-theme.md` | 博客运行时与主题化设计（v1.15 已实现）：运行时/主题职责、契约表、主题版本与预览、站点配置与独立页面 |
| `docs/desktop-sync.md` | 桌面端同步设计（v1 草案）：本地优先 + 懒加载 manifest、`version` 修订号与本地 hash、整篇冲突 + diff、离线新建、账号隔离 |
| `docs/tech-eval-vditor-wails.md` | 可行性评估与落地记录：Wails v3 已采纳；admin/blog/desktop 均已切换 Vditor + Lute（v2） |
| 各端 `README.md` | 目录结构、命令与端内细节 |
| 各端 `AGENTS.md` | 端内专属规则（探索到对应目录时自动加载） |

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
- **权限**：权限码是前后端唯一纽带——目录在 `sys_menu`（迁移只 INSERT、启动对账 fail-fast）、代码引用 `Perms` 常量；前端按服务端菜单列表注册路由、仅做显隐，接口权限后端终审（见 `docs/auth-rbac.md`）

## 常用命令速查

```bash
# Web（frontend/ 下执行）
pnpm install && pnpm typecheck
pnpm dev:admin            # http://localhost:5173
pnpm dev:blog             # http://localhost:5174（runtime dev；页面走后端 8080）
pnpm build:admin && pnpm build:blog
pnpm sync:theme           # 默认主题：packages/theme-starter → 后端兜底副本

# 桌面（desktop/ 下执行；UI 在 frontend/apps/desktop）
wails3 build              # bindings → 前端构建 → 同步 → 编译（需要先装 wails3 beta.34）
wails3 dev                # 需要图形环境
go test ./...

# 后端（backend/ 下执行；打包前先 pnpm build:blog，产物会进 jar）
./mvnw test
./mvnw spring-boot:run   # 默认 dev profile：asset-base → Vite 5174（HMR）
```

## 禁则（Guardrails）

- 桌面端 WebView 只加载本地内嵌资源，API 一律走 Go binding；令牌只存 SQLite，不进入 JS；附件 / 大载荷不走 IPC
- 桌面端内存按 WebView 进程树预算评估（见 `desktop/AGENTS.md`），新增常驻能力前先实测
- 桌面端 Go 侧保持无头可测；避免引入需要 CGO 的依赖（SQLite 用纯 Go 驱动）
- 不引入需要商业许可的组件；新增依赖前确认许可证可自由使用
- 前端依赖版本一律走根 `pnpm-workspace.yaml` 的 `catalog`；`apps/*` 之间禁止互相依赖
- 方言以 `docs/markdown-dialect.md` v2 白名单为准：允许数学、emoji、脚注、上下标、mark 与安全行内 HTML（渲染前必须清洗）；仍不收 front matter / 图片尺寸语法 / 服务端渲染

## 规则文件自身

- 本文件与 `docs/`、各端 `AGENTS.md` 随仓库提交，保证任何会话 / 工具拿到同一份约束
- 新增端内规则时写入对应目录的 `AGENTS.md`（按目录就近加载），避免膨胀本文件
