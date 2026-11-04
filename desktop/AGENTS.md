# desktop 端规则

Go 1.26+ + Wails v3（WebView）桌面端；前端是 `frontend/apps/desktop`（Vue 3 / TS，pnpm workspace 成员）。**当前已实现同步骨架 + 本地多标签 / 文档工作台（Windows；三平台验收待做）**。结构与选型见 `README.md` 与 `docs/tech-stack.md`。

## 已定技术项

- 壳 / 本地能力：Go + Wails v3.0.0-beta.34（锁版本）；services + bindings
- UI：Vue 3 + Vite + Tailwind（复用 `packages/shared`、`packages/ui`、`packages/api` 类型）
- 数据：本地优先（SQLite，`modernc.org/sqlite`；离线 + 后台同步，协议见 `docs/desktop-sync.md`）
- Markdown：**Vditor 4.0.1 + Lute**（方言 v2，与 admin / blog 同源，2026-10-31 迁移完成）；资源由 `pnpm sync:vditor` 自托管
- 分发：三平台各自构建（Wails Taskfile / CI 矩阵），不再使用 .NET NativeAOT

## 硬约束（来自选型，改动需走变更决策）

- **WebView 内存预算**：内存主要由 WebView2 / WebKitGTK 进程树决定（Windows 验收：列表 WS ≈364MB / 私有 ≈225MB；打开编辑器 ≈424MB / ≈274MB；`--disable-gpu` 私有 ≈154 / ≈198MB）。新增常驻 UI、依赖或后台能力前先评估内存；优化项（`--disable-gpu`、编辑器懒加载、裁剪 WebView 功能）需记录实测数据
- **WebView 不直连后端**：所有 API 请求走 Go `api.Do` binding；Go 注入 Bearer，令牌只存 SQLite，不进入 JS / localStorage
- **大载荷不走 IPC**：附件 / 图片用 Go 侧文件路径或流式接口；binding 只传 JSON
- **三平台**：Windows / Linux（GTK4 + WebKitGTK 6.0，Ubuntu 24.04 基线）/ macOS 11+；无交叉编译，各自构建
- **许可证**：不使用需要商业许可的组件

## 代码约定

- Go module 根在 `desktop/`；前端在 `frontend/apps/desktop/`，通过 `pnpm sync:wails` 把 `dist` 同步到 `desktop/web/dist`（`//go:embed` 不能跨 module）
- 改 Go service 签名后执行 `wails3 task bindings`，生成的 `frontend/apps/desktop/bindings/` 随仓库提交
- 前端调用 binding 一律从 `bindings/` 导入，不手写 IPC 类型
- API 类型仍来自后端 OpenAPI（`packages/api`），**禁止手写接口类型**；Go 侧只做通用转发，不复制 DTO
- 本地数据库只存桌面端私有数据；认证数据不参与内容同步 / 导出 / 备份
- **桌面端接口只走 `/api/desktop/**`**（会话端 `DESKTOP`，权限码 `desktop:note` / `desktop:post`）；`DESKTOP` 会话不能调用 `/api/admin/**`（`SessionJwtFilter` 校验）
- **同步按 `docs/desktop-sync.md`**：manifest 只写索引，不拉正文；打开时按 `base_version` 拉详情并算 hash；推送用 `version` CAS，409 转冲突
- **本地文件按 `docs/desktop-local-file.md`**：临时编辑不写 `note_local`；纳入管理走 `notes.ImportFile`；本地 / 云端文件夹是两棵**独立树**（`parent_id`，最深 5 级，同父级唯一、跨层级可同名），不做名称 / 路径映射；本地文件夹写 `folder_local(scope_id='local')`，不入 outbox、不参与同步；纳入云端时只写用户选中的云端文件夹 id；有子文件夹时禁止删除
- **多标签按 `docs/desktop-layout.md` §3（v1.2 已实现，Windows）**：文件 / 本地 / 服务端笔记统一标签栏；单 Vditor 实例，切标签清空撤销栈；标签上限 12；文件标签只存路径、激活时读盘，不存正文；`ui_state` 只存标签顺序 / 侧栏宽度
- **右键菜单（v2.0）**：窗口启用 `DefaultContextMenuDisabled`，前端统一接管 `contextmenu`（开发构建也不漏出 WebView2 默认菜单）；文件夹 / 列表 / 标签 / 文本编辑菜单统一用 `apps/desktop/src/components/ContextMenu.vue`，文本粘贴经 `@wailsio/runtime` 的 `Clipboard`，不要为单个入口另写定位与遮罩
- **图片按 `docs/desktop-media.md`（v1 已实现）**：文件写 `<stem>.assets` 相对路径；本地笔记写 `local-media/<id>` + `media/local/`；服务端笔记上传 `/api/desktop/media` 后写 `/api/media/<id>`；二进制只走 Wails 同源 `/__media/import`，禁止 base64 / JSON IPC；`/api/media/**`、`/oss/**` 由 Go 只读代理
- **发布 / 下架只在 admin**：桌面端只展示 `publishStatus` / `hasUnpublishedChanges` 并提供 `System.OpenAdmin` 深链，不新增桌面发布接口
- JSON 用 Go 标准库；不引入需要 CGO 的库（SQLite 已选纯 Go 驱动）

## 本地库结构（docs/desktop-local-schema.md）

- 表结构只走 `internal/store/migrations` 的有序迁移；`PRAGMA user_version` 是**唯一版本源**，不再用运行时 `CREATE IF NOT EXISTS` 兜底
- **已发布迁移不可修改 / 删除 / 重排**；新变更只在末尾追加编号（`%04d_*.sql` 或注册表里的 Go 函数）
- 新增 SQL 迁移：文件放 `internal/store/migrations/`，并在 `migrate.go` 的 `migrations` 注册表登记；启动时校验编号连续、文件名与编号一致
- 条件性 DDL / 数据回填用 Go 函数（`ensureColumnTx` 模式），必须幂等；常规 DDL 用 SQL
- **迁移失败或库版本高于应用 → 阻塞启动**，不自动重建 / 清空；错误写入 `desktop-error.log` 并弹原生对话框
- 迁移前备份为 `desktop.db.bak.v<N>`（只保留最近一份；全新安装跳过）；恢复由用户手动覆盖
- **`user_version` 不上报服务端**；本地 schema 与 API 契约版本解耦，协议协商见设计 §8

## 环境注意

- 本仓库开发机为 Windows，**Windows 构建 / 运行已验证**；Linux / macOS 与中文输入法（fcitx5 + Wayland、微软拼音）需实机验证
- 无图形环境时只能跑 `go test ./...`、`pnpm typecheck`、`pnpm build:desktop`；`wails3 dev` / GUI 需桌面环境

## 命令

```bash
# desktop/
wails3 build                  # bindings → 前端构建 → 同步 → 编译 bin/SimpleNotes.exe
wails3 dev                    # 开发模式（需要图形环境）
wails3 task bindings          # 只生成 TS bindings
go test ./...                 # Go 单测

# frontend/
pnpm dev:desktop              # 5175（浏览器调试 UI；binding 调用不可用）
pnpm build:desktop
```
