# 部署（3 容器：db + app + web）

> 状态：v0.2 · 2026-11-11（3 容器架构：db + app + web）
> 关联：`docs/release.md`、`desktop/README.md`、`docs/attachment-storage.md`

## 1. 快速开始

```bash
bash scripts/init-env.sh          # 生成 .env（随机 DB 口令 / JWT / root 初始口令）
docker compose up -d --build
```

- 管理端：`http://<host>:8080/admin/`
- 博客：`http://<host>:8080/`
- 初始口令：`grep ROOT_INITIAL_PASSWORD .env`

首次登录后请在个人中心修改 root 口令；`.env` 里的初始口令只在 root 仍是迁移默认口令时生效。

> **fail-fast**：root 仍是迁移里的默认口令（`admin@123456`）而 `ROOT_INITIAL_PASSWORD` 为空时，`app` 会拒绝启动并在日志里给出提示。改密之后该变量可以留空（但 `scripts/init-env.sh` 会一直保留它，留着也无害）。

受限网络 / HTTP 代理：

```bash
bash scripts/docker-build.sh all
docker compose up -d --no-build
```

端口被占用：改 `.env` 的 `SIMPLE_NOTES_PORT` 与 `ADMIN_ORIGIN`（两者要一致），再
`docker compose up -d --no-build`。放到 `.env` 里也能保证后续备份/恢复沿用同一端口。
`app` 的回环端口冲突时改 `APP_PORT`；远程 nginx 需要 `APP_BIND=0.0.0.0`（并配防火墙）。

## 2. 服务与端口

```text
web (nginx:8080) ── /admin/  静态（admin 产物）
                 ├─ /api/   → app:8081
                 ├─ /blog/  → app:8081
                 └─ /       → app:8081（博客壳）
app (8081) ────── db:5432（或外部 PostgreSQL）
```

| 服务 | 说明 |
| --- | --- |
| `db` | `postgres:17-bookworm`，named volume `simple-notes-pg` |
| `app` | Spring Boot jar（含 blog runtime），默认只绑定 `127.0.0.1:8081` |
| `web` | nginx，托管 admin 静态并反代 API / blog |

媒体与日志：

| 容器路径 | 宿主机 | 说明 |
| --- | --- | --- |
| `/data/media` | `./data/media` | LOCAL 存储的上传文件 |
| `/data/logs` | `./data/logs` | 应用日志（10MB × 30 天） |

`.env` 含数据库口令 / JWT / root 初始口令，权限 `600`，**不要提交**。

## 3. 直连已有数据库（外部库）

编辑 `.env`：

```dotenv
DB_URL=jdbc:postgresql://db.example.com:5432/simple_notes
DB_USERNAME=simple_notes
DB_PASSWORD=change-me
```

启动时跳过本地 `db`：

```bash
docker compose up -d --no-deps app web
```

说明：

- 要求 PostgreSQL **13+**（迁移使用 `gen_random_uuid()`）、UTF8；
- 数据库用户需要建表 / 改表权限（Flyway 启动时自动执行）；
- 本地 `db` 若已在运行，可 `docker compose stop db`；
- `.env` 里由 `init-env.sh` 生成的 `POSTGRES_PASSWORD` 保留即可（本地 db 不再使用，但 compose 解析需要它）；
- 外部库的备份 / 恢复由你自行负责，`scripts/docker-backup.sh` 只支持 compose 内的 `db`；
- `app` 启动入口会等待 `DB_URL` 的 host:port 可达（最多 120s），再启动 Spring Boot。

## 4. admin 裸机 nginx 部署

admin 是纯静态产物；blog 仍必须由 app 提供（不是静态 SPA）。

### 4.1 构建 admin

```bash
cd frontend
pnpm install
VITE_ADMIN_BASE=/admin/ pnpm build:admin

sudo mkdir -p /var/www/simple-notes/admin
sudo cp -r apps/admin/dist/* /var/www/simple-notes/admin/
```

### 4.2 启动 app（不带 web 容器）

```bash
# 使用本地 db
docker compose up -d db app

# 或使用外部库
docker compose up -d --no-deps app
```

`app` 默认发布在 `127.0.0.1:8081`，供同机 nginx 使用。
远程 nginx 时在 `.env` 设 `APP_BIND=0.0.0.0`，并限制防火墙 / 走内网。

### 4.3 配置 nginx

复制并修改 [`deploy/nginx/site.conf.example`](../deploy/nginx/site.conf.example)：

- `server_name`、`root /var/www/simple-notes`、TLS 证书；
- `upstream app { server 127.0.0.1:8081; }`（远程改成 app 主机）；
- 保留 `/api`、`/blog`、`/` 反代，admin 只走静态；
- 上传上限 `client_max_body_size 25m`；
- SSE 需要 `proxy_buffering off` + `proxy_read_timeout 660s`；
- 登录接口保留 `limit_req`（应用侧只信任 remoteAddr）。

`app` 的 `ADMIN_ORIGIN` 必须设为最终访问源（如 `https://notes.example.com`），用于博客预览 iframe 的 CSP。
admin 与博客建议同源（`/admin` + `/`）；若分域，admin 的 CSP 还需把博客 origin 加入 `frame-src`。

## 5. 备份与恢复

```bash
bash scripts/docker-backup.sh
# 产物：db.dump + media.tgz + .env
```

恢复：

```bash
bash scripts/docker-restore.sh backup/<时间戳>
```

- 默认恢复后启动 `app web`；裸机 nginx 用 `START_SERVICES=app`；
- 自定义媒体目录用 `DATA_DIR=/path/to/data`；
- 外部数据库不支持脚本恢复，请直接 `pg_restore`；
- Flyway 只能前进：升级前备份，回退 = 恢复备份。

## 6. 升级

```bash
bash scripts/docker-backup.sh
git pull
bash scripts/docker-build.sh all
docker compose up -d --no-build
docker compose logs -f app
```

### 6.1 使用预构建镜像（GHCR）

打 `v*` tag 后，GitHub Actions 会把镜像推到 `ghcr.io/<owner>/simple-notes-{app,web}:<version>`（同时更新 `latest`）。
服务器不必构建，只拉镜像：

```dotenv
# .env
APP_IMAGE=ghcr.io/<owner>/simple-notes-app:0.2.0
WEB_IMAGE=ghcr.io/<owner>/simple-notes-web:0.2.0
```

```bash
docker compose pull app web
docker compose up -d --no-build
```

- 包默认继承仓库可见性；通过网页把 package 设为 public，或 `docker login ghcr.io` 后再拉；
- 目前只构建 `linux/amd64`，arm64 见 `.github/workflows/images.yml` 顶部说明；
- 镜像里的 `RELEASE_VERSION` 取自 tag，`/api/version` 可直接核对。

## 7. 边界与注意

- **单 app 实例**：授权快照 / 登录限流 / AI 草稿与会话在进程内；多副本需共享缓存，1.x 再做。
- **媒体**：默认 LOCAL + `./data/media`；多 app 实例或跨主机时改用 R2 / DATABASE，或共享存储。
- **TLS**：由 web（或裸机 nginx）终止；compose 内 web 默认只监听 8080 HTTP。
- **arm64**：`docker build --platform linux/arm64 ...`；最终仍需目标 NAS 实机冒烟。
- **admin 可静态托管，blog 不可**：博客页面壳由 app 按站点配置/主题生成，`/blog/**` 与 `/` 必须反代。
- **日志**：`docker compose logs`；应用文件日志在 `./data/logs`。
