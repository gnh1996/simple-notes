# simple-notes 项目规则

随手记应用兼个人博客：`backend`（Spring Boot 服务端）+ `frontend`（Vue3 Web 工作区：管理端 + 博客前台）+ `desktop`（Avalonia 桌面端）。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| `docs/tech-stack.md` | 技术选型总览（2026-10-18 冻结）与残余待定项 |
| `docs/auth-rbac.md` | 认证与权限设计（RBAC：三端入口 / admin 菜单 / 按钮与接口权限；JWT 会话；v1 定稿） |
| `docs/markdown-dialect.md` | Markdown 内容方言规范（白名单 / 禁区 / 三端配置对齐） |
| `docs/markdown-dialect-fixture.md` | 方言验收样本：新增语法必须三端渲染比对 |
| 各端 `README.md` | 目录结构、命令与端内细节 |
| 各端 `AGENTS.md` | 端内专属规则（探索到对应目录时自动加载） |

## 当前阶段：设计先行

- 功能设计文档定稿后才进入实现；涉及未设计领域的改动，先补设计（写文档）或征询，不要直接写实现
- 已冻结的选型（见 `docs/tech-stack.md`）默认不再回头讨论；若有新信息推翻结论，按"变更决策"处理：更新对应文档并记录理由
- 决策记录位置：跨端 / 阶段性决策 → `docs/`；端内选型 → 各端 README 的选型表

## 跨端关键约定

- **响应式（Web）**：`useBreakpoint()`（`@simple-notes/shared`）是全项目唯一的断点来源，禁止 UA / 设备类型判断；同一路由与逻辑只有一份，只在展示层按断点分叉
- **Markdown**：遵守 `docs/markdown-dialect.md` 白名单；新增语法必须先在 fixture 中完成三端验收；admin 与 blog 的 md-editor-v3 全局 `config()` 必须一致（共享配置模块）
- **API 契约**：后端 OpenAPI 为单一来源，Web / 桌面端一律使用生成客户端，禁止手写接口类型
- **数据**：后端 PostgreSQL + Flyway（迁移只追加、不改历史；已在任一环境执行过的脚本不可修改 / 删除，回退只能新增更高版本补偿）；桌面端本地优先（离线可用 + 后台同步）
- **权限**：权限码是前后端唯一纽带——目录在 `sys_menu`（迁移只 INSERT、启动对账 fail-fast）、代码引用 `Perms` 常量；前端按服务端菜单列表注册路由、仅做显隐，接口权限后端终审（见 `docs/auth-rbac.md`）

## 常用命令速查

```bash
# Web（frontend/ 下执行）
pnpm install && pnpm typecheck
pnpm dev:admin            # http://localhost:5173
pnpm dev:blog             # http://localhost:5174
pnpm build:admin && pnpm build:blog

# 桌面（desktop/ 下执行）
dotnet build -c Release
dotnet run --project src/SimpleNotes.Desktop   # 需要图形环境

# 后端（backend/ 下执行）
./mvnw test
```

## 禁则（Guardrails）

- 不内嵌 WebView 做桌面端 Markdown 预览 —— 低内存目标是桌面端选型的核心约束
- 不引入需要商业许可的组件（如 Avalonia Pro 控件）；新增依赖前确认许可证可自由使用
- 桌面端代码保持 AOT / 裁剪友好：JSON 用源生成，避免反射与动态代码生成
- 前端依赖版本一律走根 `pnpm-workspace.yaml` 的 `catalog`；`apps/*` 之间禁止互相依赖
- 方言禁区不入库：裸 HTML、front matter、数学公式、脚注等（见方言规范的 v1 白名单）

## 规则文件自身

- 本文件与 `docs/`、各端 `AGENTS.md` 随仓库提交，保证任何会话 / 工具拿到同一份约束
- 新增端内规则时写入对应目录的 `AGENTS.md`（按目录就近加载），避免膨胀本文件
