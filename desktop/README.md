# Simple Notes · 桌面端（Wails v3）

桌面客户端：随手记与博客写作、发布到 Web 博客端。UI 为 **Vue 3 + TypeScript**，壳与本地能力由 **Go + Wails v3** 提供。

> **当前阶段：技术骨架（POC 已验证可构建）**。只包含窗口、共享 Markdown 编辑器、Go 侧 HTTP 转发与本地 SQLite 骨架；领域模型、同步协议、页面信息架构随功能设计定稿后再实现。

## 架构

```text
desktop/（Go module：cc.naihao/simplenotes/desktop）
├── main.go                 Wails 应用装配（窗口 / services / 内嵌资源）
├── internal/
│   ├── api/                通用 HTTP 转发（Bearer 注入；前端不直连后端）
│   ├── store/              SQLite（modernc.org/sqlite，纯 Go）
│   └── system/             运行状态（版本 / API 地址 / 本地库 / 是否有令牌）
├── web/dist/               //go:embed 目标；由 pnpm sync:wails 从 Vue 构建产物同步
├── build/                  Wails 平台构建资产与 Taskfile
└── Taskfile.yml            build / dev / run / package / bindings

frontend/apps/desktop/（pnpm workspace 成员：@simple-notes/desktop）
├── src/                    Vue 应用（复用 packages/shared、packages/ui）
├── bindings/               wails3 生成的 TS bindings（随仓库提交）
└── scripts/sync-wails.mjs  把 dist 同步到 desktop/web/dist
```

**为什么前端在 `frontend/apps/desktop`**：复用 pnpm catalog、`workspace:*` 与共享包（`packages/api` / `shared` / `ui`）。
Go 的 `//go:embed` 不能引用 module 之外的路径，因此构建后把 `dist` 同步进 `desktop/web/dist`。

## 技术栈

| 项 | 结论 |
| --- | --- |
| 壳 | **Wails v3.0.0-beta.34**（锁版本；MIT/ISC） |
| 语言 | Go **1.26+**（本机 1.27.3）；三平台各自构建（CGO + 平台 WebView，无交叉编译） |
| UI | Vue 3.5 + Vite 8 + TypeScript 6 + Tailwind v4 |
| 本地存储 | SQLite（`modernc.org/sqlite`），库文件在 `os.UserConfigDir()/simple-notes/desktop.db` |
| 网络 | Go `net/http`；前端通过 Wails binding `api.Do` 调用，**WebView 不直接 fetch 后端** |
| Markdown | 过渡期：md-editor-v3 + `@simple-notes/shared` 的 `configureMarkdown()`；**待迁移**到 Vditor + Lute（admin / blog 已切换） |
| 平台 | Windows（WebView2）、macOS 11+（WKWebView）、Linux（GTK4 + WebKitGTK 6.0；Ubuntu 24.04 基线） |

## 命令

先安装 CLI（版本必须与 `go.mod` 一致）：

```bash
go install github.com/wailsapp/wails/v3/cmd/wails3@v3.0.0-beta.34
```

在 `desktop/` 下执行：

| 命令 | 说明 |
| --- | --- |
| `wails3 build` | 生成 bindings → 构建前端 → 同步产物 → 编译 `bin/SimpleNotes.exe` |
| `wails3 dev` | 开发模式（Vite HMR；需要图形环境） |
| `wails3 task bindings` | 只重新生成 TS bindings（改 Go service 签名后） |
| `wails3 package` | 平台安装包（Windows NSIS；Linux deb/rpm/AppImage；macOS .app） |
| `go test ./...` | Go 单元测试（store 等无头逻辑） |

前端单独调试（在 `frontend/` 下）：

```bash
pnpm dev:desktop          # 5175（普通浏览器可看 UI，调用 binding 会失败）
pnpm build:desktop        # 只构建；之后在 desktop/ 执行 wails3 build
```

> `wails3 build` 会自动执行 `pnpm install` / `pnpm run build` / `pnpm run sync:wails`，一般不需要手动跑前端构建。

## API 访问约定

- 前端使用 `@simple-notes/api` 的 OpenAPI 生成类型；请求通过 Go binding `api.Do({ method, path, body })` 转发。
- Go 侧从 SQLite 读取 JWT 并注入 `Authorization: Bearer`；**令牌不进入 JS / localStorage**。
- 后端地址默认 `http://localhost:8080/api`，可用 `SIMPLE_NOTES_API_BASE` 覆盖。
- 不走 WebView 直连的原因：自定义 scheme 的 origin 是 `wails://wails` / `http://wails.localhost`，直连需要后端 CORS，且令牌会暴露在 JS。
- 大文件 / 附件不要走 binding：用 Go 侧文件对话框拿路径后直接上传，或走流式接口。

## 内存基线（POC 实测）

Windows 11 / WebView2，空闲 20s 后按进程树统计：

| 场景 | 工作集合计 | 私有内存合计 |
| --- | --- | --- |
| Wails 官方模板（无编辑器） | ≈361MB | ≈232MB |
| 本骨架（md-editor-v3，过渡） | ≈390–403MB | ≈254MB |
| 本骨架 + `SIMPLE_NOTES_WEBVIEW_ARGS="--disable-gpu"` | ≈388MB | ≈191MB |

结论：内存主要由 WebView2 进程树决定（Go 进程仅 ~38MB），编辑器约占 40MB。`--disable-gpu` 能明显降低私有内存，但会影响滚动 / 动画，是否默认开启需实机体验后决定。

可继续优化的方向：延迟加载编辑器（路由级 code split）、`DisabledFeatures` / `AdditionalBrowserArgs` 裁剪 WebView2 功能、减少常驻面板。

## 平台与验证

- 本仓库开发机为 Windows；**Windows 构建 / 运行已验证**（2026-10-25）。
- Linux / macOS 尚未实机验证：需要 GTK4 + WebKitGTK 6.0 / Xcode CLI；中文输入法（Windows 微软拼音、Linux fcitx5 + Wayland）必须在实机走查。
- 当前开发容器若无图形环境，只能跑 `go test` / 前端 typecheck / build；GUI 需桌面环境。

## 下一步（功能设计阶段）

- [ ] 领域模型与本地存储 schema（笔记、草稿、发布状态、标签）
- [ ] 同步协议（离线补发、冲突策略、本地/远端 ID 映射）
- [ ] 登录 / 会话刷新（Go 侧完成，token 落 SQLite）
- [ ] 信息架构与编辑体验（复用 admin 的编辑器与方言配置）
- [ ] 内存 / 启动时间门槛与三平台实机验收
