# 发版与版本确定

> 状态：v0.1 落地（2026-11-07）
> 关联：`docs/desktop-local-schema.md` §8（版本轴划分与协议协商）、各端 `AGENTS.md`

## 1. 版本轴（一句话）

| 版本轴 | 载体 | 用途 | 展示位置 |
| --- | --- | --- | --- |
| **产品发布版本** | Git tag `vX.Y.Z`（构建时注入各产物） | 回答"这是哪次发布"；产物命名、发布说明、排障 | 关于 / 诊断信息 |
| API 契约版本 | OpenAPI `info.version`（常量 `v1`，`OpenApiConfig.CONTRACT_VERSION`） | 生成客户端类型、判断契约代际 | `/api/version` 的 `contract` |
| 桌面协议版本 | 触发后实施（`X-Desktop-Protocol` + `/api/desktop/meta`，见 local-schema §8.7） | 服务端与桌面端兼容性强制 | 仅错误 / 诊断 |
| 桌面本地 schema | `PRAGMA user_version` | 本地迁移 | 仅诊断 |
| 内容修订号 | `note.version` | 同步 CAS / 冲突 | 业务界面已有 |

**纪律：产品版本只负责"这是哪次发布"，不承担兼容性判断。** 不要拿产品版本做功能门控，
不要用 `note.version` / `user_version` / OpenAPI `v1` 推导产品版本，
也不要把 `info.version` 改成产品版本（否则每次发版生成类型都变）。

## 2. 版本来源（构建注入，运行时不读 tag）

发布后的二进制里没有仓库，**版本必须在构建时注入**，运行时只读注入值：

| 产物 | 版本来源 | 本地默认值 | 显示 |
| --- | --- | --- | --- |
| 后端 jar | Maven `-Drevision=X.Y.Z` → `build-info` → `BuildProperties`；`APP_COMMIT` / `APP_BUILD_TIME` 环境变量 | `0.0.0-dev` / `unknown` | `GET /api/version` → `{version, commit, buildTime, contract}` |
| 管理端 | `VITE_APP_VERSION` 环境变量（Vite 构建时注入） | `0.0.0-dev` | 账户菜单：管理端 vX · 服务端 vY |
| 博客 runtime | 随 jar 发布，版本 = 服务端版本，不单独维护 | — | 不展示 |
| 桌面端 | Task 变量 `VERSION= X.Y.Z COMMIT=… BUILD_TIME=…` → `-ldflags -X` 烘焙进 `system` 包 | `0.0.0-dev` / `unknown` | 账户菜单：桌面端 vX · 服务端 vY/未知；诊断信息首行 |

服务端版本查询统一走 `GET /api/version`（匿名）。桌面端经 Go binding
`SystemService.ServerVersion()` 转发，**WebView 不直连后端**；失败时展示"未知"，
不阻塞登录 / 本地功能。管理端用 `src/api` 的 `metaApi.version()`（生成类型，同源代理）。

## 3. 发版流程

前置：工作区干净（`git status --short` 无输出），发布提交已在 `main`。

```bash
# 1. 更新平台元数据（读 tag 参数；默认只检查，加 --apply 才写文件）
node scripts/release-version.mjs v0.2.0
node scripts/release-version.mjs v0.2.0 --apply
# 2. 提交元数据，再打 tag（tag 必须打在最终发布提交上）
git add -A && git commit -m "chore(release): v0.2.0 元数据"
git tag -a v0.2.0 -m "v0.2.0"

# 3. 后端（含管理端 / 博客 runtime）
cd frontend && pnpm install && pnpm build:admin && pnpm build:blog && cd ..
BUILD_TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
VITE_APP_VERSION=0.2.0 APP_COMMIT=$(git rev-parse --short HEAD) \
  APP_BUILD_TIME=$BUILD_TS \
  ./backend/mvnw "-Drevision=0.2.0" "-Dproject.build.outputTimestamp=$BUILD_TS" -DskipTests package
# 产物：backend/target/simple-notes-0.2.0.jar
# outputTimestamp 同时让 jar 可复现、让 /api/version 的 buildTime 为真实构建时刻；
# Windows PowerShell：-D 参数必须加引号（否则会被拆成非法 phase）；
# 环境变量用 $env:VITE_APP_VERSION="0.2.0" 等形式设置。

# 4. 桌面端（三平台各自构建，无交叉编译；在各平台本机执行同一条命令）
cd desktop && wails3 build VERSION=0.2.0 COMMIT=<shortsha> BUILD_TIME=<iso8601>
# Linux / macOS 在各自机器上执行同一条命令
```

`--apply` 更新的文件（版本号去掉 `v`，Windows manifest 补 `.0` 四段）：

- `desktop/build/config.yml`（`wails3 update build-assets` 的源）
- `desktop/build/windows/info.json`、`wails.exe.manifest`、`nsis/wails_tools.nsh`
- `desktop/build/darwin/Info.plist`（`CFBundleShortVersionString` / `CFBundleVersion`）
- `desktop/build/linux/nfpm/nfpm.yaml`
- `frontend/apps/desktop/package.json`

## 4. 发版校验（必须全部通过）

```bash
curl -s http://<server>/api/version
# → {"version":"0.2.0","commit":"…","buildTime":"…","contract":"v1"}

go test ./...                          # desktop/
./mvnw test                            # backend/（需 Docker，Testcontainers）
pnpm typecheck                         # frontend/
```

人工确认：管理端账户菜单显示"管理端 v0.2.0 · 服务端 v0.2.0"；
桌面端账户菜单显示"桌面端 v0.2.0 · 服务端 v0.2.0"，诊断信息首行一致。

## 5. 禁则

- **发布构建拒绝 dirty**：`git status --short` 必须为空；脚本 `--apply` 前会检查。
- tag 一旦发布不可移动 / 覆盖；回退只能发新版本。
- 不要手工改 7 处平台元数据，一律走脚本（漂移必然发生）。
- dev 构建禁止伪装成发布版本：默认 `0.0.0-dev` 就是用来区分的。
- 桌面端自动更新 / "已是最新版本"提示：在真正实现更新检查前不做，
  关于页只做展示，不做"检查更新"按钮。
- 需要独立发版节奏（桌面 hotfix 不带服务端）或自托管分发时，
  再拆成独立版本号 + 兼容矩阵，在此之前统一版本。
