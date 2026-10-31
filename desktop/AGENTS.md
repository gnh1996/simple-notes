# desktop 端规则

Go 1.26+ + Wails v3（WebView）桌面端；前端是 `frontend/apps/desktop`（Vue 3 / TS，pnpm workspace 成员）。**当前为技术骨架**：功能设计定稿后才进入实现。结构与选型见 `README.md` 与 `docs/tech-stack.md`。

## 已定技术项

- 壳 / 本地能力：Go + Wails v3.0.0-beta.34（锁版本）；services + bindings
- UI：Vue 3 + Vite + Tailwind（复用 `packages/shared`、`packages/ui`、`packages/api` 类型）
- 数据：本地优先（SQLite，`modernc.org/sqlite`；离线 + 后台同步，协议待设计）
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
- **发布 / 下架只在 admin**：桌面端只展示 `publishStatus` / `hasUnpublishedChanges` 并提供 `System.OpenAdmin` 深链，不新增桌面发布接口
- JSON 用 Go 标准库；不引入需要 CGO 的库（SQLite 已选纯 Go 驱动）

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
