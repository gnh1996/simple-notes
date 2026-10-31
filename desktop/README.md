# Simple Notes · 桌面端（Wails v3）

桌面客户端：随手记与博客写作、发布到 Web 博客端。UI 为 **Vue 3 + TypeScript**，壳与本地能力由 **Go + Wails v3** 提供。

> **当前阶段：技术骨架 + 编辑器 POC（可构建、可运行）**。包含窗口、Vditor + Lute 编辑器（方言 v2）、Go 侧 HTTP 转发、SQLite 骨架；同步协议见 `docs/desktop-sync.md`（M0–M4），页面信息架构随功能设计推进。

## 架构

```text
desktop/（Go module：cc.naihao/simplenotes/desktop）
├── main.go                 Wails 应用装配（窗口 / services / 内嵌资源）
├── internal/
│   ├── api/                通用 HTTP 转发（Bearer 注入；前端不直连后端）
│   ├── auth/               登录 / 登出 / scope（令牌只落 SQLite）
│   ├── notes/              本地索引查询、打开即拉正文、内容 hash
│   ├── syncsvc/            manifest 索引同步（游标 + 墓碑）
│   ├── store/              SQLite（认证 + scope / note_local / folder_local / outbox）
│   └── system/             运行状态（版本 / API 地址 / 本地库 / 会话）
├── web/dist/               //go:embed 目标；由 pnpm sync:wails 从 Vue 构建产物同步
├── build/                  Wails 平台构建资产与 Taskfile
└── Taskfile.yml            build / dev / run / package / bindings

frontend/apps/desktop/（pnpm workspace 成员：@simple-notes/desktop）
├── src/                    Vue 应用（登录 / 索引列表 / 只读预览 / 编辑器 POC）
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
| Markdown | **Vditor 4.0.1 + Lute**（方言 v2，与 admin / blog 同源）；配置取自 `@simple-notes/markdown`；资源由 `pnpm sync:vditor` 自托管到 `public/vditor` |
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

## 同步骨架（M0–M4，见 `docs/desktop-sync.md`）

- **登录**：`POST /api/auth/login`（`client=DESKTOP`），令牌只写 SQLite；服务器地址可配，自动补 `/api`
- **索引同步**：`GET /api/desktop/notes/sync` / `posts/sync`（keyset 游标 + 墓碑）+ `note-folders`，只写本地索引，不拉正文
- **打开即拉取**：`GET /api/desktop/notes|posts/{id}`，按 `base_version` 判断是否需要拉取；拉完算内容 hash 写回
- **编辑与推送（M1）**：Vditor IR 编辑，800ms 防抖本地保存 + `PUT` 推送；`version` CAS，409 显示冲突横幅（以本地 / 服务端为准）；离线保留 dirty 待重试
- **删除与文件夹（M2）**：笔记删除走墓碑 + 软删除；删除冲突可「保留为本地副本 / 放弃本地」；文件夹新建 / 改名 / 删除，失败进 outbox
- **outbox（M2）**：离线写操作入队，启动 / 同步前自动重放，指数退避 + 手动「重试」；删除冲突与待重试状态在列表 / 底栏可见
- **离线新建与冲突对照（M3）**：客户端 UUID 新建（正文可空白），离线入队、重连后创建；冲突弹窗提供行级 diff，「以本地 / 服务端为准」时被覆盖一侧自动存为 `scope=local` 副本（列表标「本地」）
- **发布留在 admin（M4）**：桌面端展示 `publishStatus` / `hasUnpublishedChanges`，提供「管理端发布」深链（系统浏览器，默认 `<server>/admin/posts/{id}`，可用 `SIMPLE_NOTES_ADMIN_BASE` 覆盖）；不做发布 / 下架
- **冲突静默对齐（M4）**：409 时先比内容 hash，一致（例如仅发布状态变化）则直接对齐版本，不弹冲突
- **权限**：`entry:desktop` + `desktop:note` / `desktop:post`（`V13` 迁移）；`DESKTOP` 会话不能调用 `/api/admin/**`
- **隔离**：本地表按 `scope_id` 归属；切换账号只显示当前 scope 与纯本地数据

## 内存基线（POC 实测）

Windows 11 / WebView2，空闲 20s 后按进程树统计：

| 场景 | 工作集合计 | 私有内存合计 |
| --- | --- | --- |
| Wails 官方模板（无编辑器） | ≈361MB | ≈232MB |
| md-editor-v3 骨架（2026-10-25 基线） | ≈390–403MB | ≈254MB |
| **Vditor + Lute（2026-10-31，当前）** | **≈437MB** | **≈298MB** |
| 同步骨架 M0：索引列表（无预览） | ≈375MB | ≈227MB |
| 同步骨架 M0：打开一篇（Vditor 预览） | ≈403MB | ≈256MB |
| 同步骨架 M1：编辑一篇（Vditor IR ×1） | ≈423MB | ≈272MB |
| **Win 验收：列表（编辑器懒加载）** | **≈364MB** | **≈225MB** |
| **Win 验收：打开一篇（编辑器按需加载）** | **≈424MB** | **≈274MB** |
| **Win 验收：关闭编辑器后** | ≈424MB | ≈276MB（WebView 未回收） |
| **Win 验收 + `--disable-gpu`：列表** | ≈357MB | **≈154MB** |
| **Win 验收 + `--disable-gpu`：编辑器** | ≈414MB | **≈198MB** |
| Vditor + Lute + `SIMPLE_NOTES_WEBVIEW_ARGS="--disable-gpu"` | ≈424MB | ≈210MB |

结论：内存主要由 WebView2 进程树决定（Go 进程 40–53MB），Vditor 编辑器相对 md-editor-v3 增加约 24MB WS / 37MB 私有（Lute `lute.min.js` 3.74MB + hljs + KaTeX + IR DOM）。`--disable-gpu` 能明显降低私有内存（−70MB 左右），但会影响滚动 / 动画，是否默认开启需实机体验后决定。

### Windows 验收（2026-10-31）

| 项 | 结果 |
| --- | --- |
| 启动（进程 → WebView 就绪） | 605 / 605 / 900 ms（3 次） |
| 页面 DCL / load | 56–68 ms / 92–119 ms |
| 缩放 | 125%（`devicePixelRatio = 1.25`，窗口 1166×743 CSS px） |
| 登录持久化 / 索引同步 | 通过（token 只存 SQLite） |
| 打开 / 编辑 / 推送 | 通过（标题改动 v17 → v18，恢复 v19） |
| 删除 / 文件夹 / outbox | 通过（M2–M3 回归） |
| 冲突 diff / 副本 | 通过（M3 回归） |
| 文章发布状态 / 管理端深链 | 通过（草稿 / 已发布 / 已下架 + 按钮） |
| 中文输入法 | 需人工验收（自动化无法模拟真实 IME 组合输入） |

**优化记录**

- 编辑器（Vditor + Lute）改为 `defineAsyncComponent` 懒加载：入口 JS 由 **434KB → 115KB**，编辑器 chunk 322KB 首次打开时才加载；列表态内存 375 → 364MB（私有 227 → 225MB）。
- 编辑器挂载后内存与优化前基本持平（424MB / 274MB），说明主要成本仍是 Vditor IR + Lute，而不是入口 JS。
- 关闭编辑器（`关闭` 按钮）不会显著释放 WebView 内存（424/276），Chromium 保留堆；不建议以此作为内存手段。
- `--disable-gpu` 仍是单项收益最大的开关（列表私有 225 → 154MB，编辑态 274 → 198MB），默认关闭，作为低内存环境的可选参数保留。

可继续优化的方向：延迟加载编辑器（路由级 code split）、`DisabledFeatures` / `AdditionalBrowserArgs` 裁剪 WebView2 功能、减少常驻面板。

## 平台与验证

- 本仓库开发机为 Windows；**Windows 构建 / 运行已验证**（2026-10-25）。
- Linux / macOS 尚未实机验证：需要 GTK4 + WebKitGTK 6.0 / Xcode CLI；中文输入法（Windows 微软拼音、Linux fcitx5 + Wayland）必须在实机走查。
- 当前开发容器若无图形环境，只能跑 `go test` / 前端 typecheck / build；GUI 需桌面环境。

## 下一步（功能设计阶段）

- [x] 领域模型与本地存储 schema（笔记、草稿、发布状态、标签；见 `docs/desktop-sync.md`）
- [x] 同步协议设计（懒加载 manifest、冲突策略、离线新建、账号隔离；见 `docs/desktop-sync.md`）
- [x] M0：登录 / 索引同步 / 列表 / 打开即拉取 / 只读预览
- [x] M1：本地编辑 + `version` CAS 推送 + 409 冲突处理
- [x] M2：删除 / 墓碑 / 文件夹写 / outbox 重试
- [x] M3：离线新建（客户端 UUID）/ 冲突 diff / 副本
- [x] M4：发布状态展示 + 管理端深链 + 冲突静默对齐（发布 / 下架只在 admin）
- [ ] 三平台实机验收（Linux / macOS、输入法、HiDPI）与启动 / 内存打磨
- [x] 编辑器与方言（Vditor 4.0.1 + Lute，2026-10-31）
- [ ] 内存 / 启动时间门槛与三平台实机验收
