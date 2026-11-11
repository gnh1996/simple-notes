# 发版与版本确定

> 状态：v0.1 落地（2026-11-07）
> 关联：`docs/desktop-local-schema.md` §8（版本轴划分与协议协商）、各端 `AGENTS.md`

## 1. 版本轴（一句话）

**Git tag 是产品版本的唯一来源**（`vX.Y.Z`；语义：X 主版本/破坏性 · Y 功能 · Z 修复；预发布 `vX.Y.Z-rc.1`）。
各构建从 git 自动推导（`scripts/git-version.mjs` 为唯一的推导实现），人全程不输入版本号；
显式环境变量（`RELEASE_VERSION` / `RELEASE_COMMIT` / `RELEASE_BUILD_TIME`）仅给无 git 的 CI 构建用。

| 版本轴 | 载体 | 用途 | 展示位置 |
| --- | --- | --- | --- |
| **产品发布版本** | Git tag（构建时自动注入各产物） | 回答"这是哪次发布"；产物命名、发布说明、排障 | 关于 / 诊断信息 |
| API 契约版本 | OpenAPI `info.version`（常量 `v1`，`OpenApiConfig.CONTRACT_VERSION`） | 生成客户端类型、判断契约代际 | `/api/version` 的 `contract` |
| 桌面协议版本 | 触发后实施（`X-Desktop-Protocol` + `/api/desktop/meta`，见 local-schema §8.7） | 服务端与桌面端兼容性强制 | 仅错误 / 诊断 |
| 桌面本地 schema | `PRAGMA user_version` | 本地迁移 | 仅诊断 |
| 内容修订号 | `note.version` | 同步 CAS / 冲突 | 业务界面已有 |

**纪律：产品版本只负责"这是哪次发布"，不承担兼容性判断。** 不要拿产品版本做功能门控，
不要用 `note.version` / `user_version` / OpenAPI `v1` 推导产品版本，
也不要把 `info.version` 改成产品版本（否则每次发版生成类型都变）。

## 2. 版本来源（构建时注入，运行时不读 tag）

发布后的二进制里没有仓库，**版本必须在构建时注入**，运行时只读注入值。
推导规则（`git describe --tags --dirty --always` 去掉 `v` 前缀）：
tag 上构建得干净版本（`0.2.0`）；tag 之后得 `0.2.0-3-gabc1234`；
脏树自动带 `-dirty` 后缀；无 git 时回退 `0.0.0-dev`（此时它表示"无版本信息"，不是"本地构建"）。
构建时刻统一取**提交时刻**（`git log -1 --format=%cI`），同一提交反复构建结果一致。

| 产物 | 版本来源 | 显示 |
| --- | --- | --- |
| 后端 jar | `scripts/build-backend.mjs` → `-Drevision` → `build-info` → `BuildProperties`；commit 只来自运行时 `APP_COMMIT`（不进 build-info） | `GET /api/version` → `{version, commit, buildTime, contract}` |
| 管理端 | `vite.config.ts` 从 git 推导 → `define __APP_VERSION__` 烘焙 | 账户菜单：管理端 vX · 服务端 vY |
| 博客 runtime | 随 jar 发布，版本 = 服务端版本，不单独维护 | 不展示 |
| 桌面端 | `scripts/build-desktop.mjs` → Task 变量 → `-ldflags -X` 烘焙进 `system` 包 | 账户菜单：桌面端 vX · 服务端 vY/未知；诊断信息首行 |

服务端版本查询统一走 `GET /api/version`（匿名）。桌面端经 Go binding
`SystemService.ServerVersion()` 转发，**WebView 不直连后端**；失败时展示"未知"，
不阻塞登录 / 本地功能。管理端走 `src/api` 的 `metaApi.version()`（生成类型，同源代理）。

## 3. 发版流程

前置：工作区干净（`git status --short` 无输出），发布提交已在 `main`。
全程不需要手写版本号：元数据脚本读 tag 参数，三处构建自动读 git。

```bash
# 1. 更新平台元数据（读 tag 参数；默认只检查，加 --apply 才写文件）
node scripts/release-version.mjs v0.2.0
node scripts/release-version.mjs v0.2.0 --apply
# 2. 提交元数据（tag 必须打在最终发布提交上，所以先提交再打 tag）
git add -A && git commit -m "chore(release): v0.2.0 元数据"
git tag -a v0.2.0 -m "v0.2.0"

# 3. 后端 jar（含博客 runtime；管理端由 web 镜像构建，前端构建不需要版本环境变量）
cd frontend && pnpm install && pnpm build:admin && pnpm build:blog && cd ..
node scripts/build-backend.mjs --skip-tests   # 可加 --dry-run 只打印命令
# 产物：backend/target/simple-notes-0.2.0.jar

# 3b. Docker 镜像（3 容器：app + web；db 用官方 postgres 镜像）
docker build --target app -t simple-notes-app:0.2.0 \
  --build-arg RELEASE_VERSION=0.2.0 -f docker/Dockerfile .
docker build --target web -t simple-notes-web:0.2.0 \
  --build-arg RELEASE_VERSION=0.2.0 -f docker/Dockerfile .
# 受限网络 / HTTP 代理：bash scripts/docker-build.sh all
# 部署 / 备份 / 外部数据库 / 裸机 nginx 见 docs/deployment.md

# 4. 桌面端（三平台各自构建，无交叉编译；在各平台本机执行同一条命令）
node scripts/build-desktop.mjs --package      # 可加 --dry-run 只打印命令
```

部署 jar 时设置运行时环境（commit 只从这里来，不进 build-info）：
`APP_COMMIT=<shortsha>`（`APP_BUILD_TIME` 可选，缺省用烘焙值）。
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
./mvnw test                            # backend/（需 Docker，Testcontainers；
                                       # Windows 用 WSL Docker：先起 scripts/wsl-docker-proxy.py，
                                       # 再设 $env:DOCKER_HOST='tcp://localhost:2375'）
pnpm typecheck                         # frontend/
```

人工确认：管理端账户菜单显示"管理端 v0.2.0 · 服务端 v0.2.0"；
桌面端账户菜单显示"桌面端 v0.2.0 · 服务端 v0.2.0"，诊断信息首行一致。

## 5. 禁则

- **发布构建拒绝 dirty**：`git status --short` 必须为空；脚本 `--apply` 前会检查。
- tag 一旦发布不可移动 / 覆盖；回退只能发新版本。
- 不要手工改 7 处平台元数据，一律走脚本（漂移必然发生）。
- dev 构建禁止伪装成发布版本：脏树自动带 `-dirty` 后缀，无 git 时才回退 `0.0.0-dev`；
  官方发布物必须来自干净的 tag 提交（由上一条保证）。
- 桌面端自动更新 / "已是最新版本"提示：在真正实现更新检查前不做，
  关于页只做展示，不做"检查更新"按钮。
- 需要独立发版节奏（桌面 hotfix 不带服务端）或自托管分发时，
  再拆成独立版本号 + 兼容矩阵，在此之前统一版本。
