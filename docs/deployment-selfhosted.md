# 自用双机部署（香港反代 + 云服务器 app/db）

> 状态：v0.1（2026-11-15，首个测试版本 v0.1.0-rc.1）
> 关联：`docs/release.md`、`docs/deployment.md`、`deploy/nginx/subdomains.conf.example`、`deploy/compose.selfhosted.yaml`

## 1. 拓扑

```text
用户
  │  HTTPS（Cloudflare 代理 / TLS / 缓存）
  ▼
香港服务器（nginx 1.22）
  ├─ simple-notes.<domain>   admin 静态：/var/www/simple-notes/admin
  │    └─ /api/              proxy → 10.8.0.2:8081（WireGuard）
  └─ blog.<domain>           整站 proxy → 10.8.0.2:8081（WireGuard）
                                    │
                          WireGuard（10.8.0.1 ↔ 10.8.0.2）
                                    │
腾讯云服务器（Docker Engine + Compose）
  ├─ app 容器 8081（只绑定 10.8.0.2，不暴露公网）
  │    ├─ Spring Boot API
  │    ├─ 博客壳 / blog runtime
  │    └─ 挂载：./data/media、./data/logs
  └─ db 容器 5432（compose 内网，不映射宿主机端口）
       └─ named volume simple-notes-pg
```

职责边界：

- **香港**：只做 TLS 终止后的静态托管与反向代理；不跑 Docker、不构建、不放数据库。
- **腾讯云**：只跑 app + db；app 端口绑 VPN 地址，数据库不对外。
- **Cloudflare**：两个子域都走代理（橙云）；源站证书用 Cloudflare Origin 通配证书。

真实主机、IP、密钥路径、域名和证书路径记录在本地 `.local/deploy.md`（已被 `.gitignore` 忽略），本文件不写这些值。

## 2. 占位符

| 占位符 | 含义 | 实际值 |
| --- | --- | --- |
| `<HK_HOST>` | 香港服务器 SSH 地址 | `.local/deploy.md` |
| `<APP_HOST>` | 腾讯云 SSH 地址 | `.local/deploy.md` |
| `<ADMIN_HOST>` | admin 子域（如 `simple-notes.example.com`） | `.local/deploy.md` |
| `<BLOG_HOST>` | 博客子域（如 `blog.example.com`） | `.local/deploy.md` |
| `<VPN_HK_IP>` | 香港 WireGuard 地址（如 `10.8.0.1`） | `.local/deploy.md` |
| `<VPN_APP_IP>` | 腾讯云 WireGuard 地址（如 `10.8.0.2`） | `.local/deploy.md` |
| `<CERT_FILE>` / `<KEY_FILE>` | Cloudflare Origin 证书 / 私钥路径 | `.local/deploy.md` |
| `<APP_IMAGE>` | `ghcr.io/<owner>/simple-notes-app:<version>` | `.local/deploy.md` |

## 3. 一次性初始化

### 3.1 DNS 与 Cloudflare

1. 两个子域都加 A 记录指向 `<HK_HOST>`，**开启代理（橙云）**。
2. SSL/TLS 模式保持 **Full (strict)**；源站证书使用 Cloudflare Origin 通配证书（覆盖 `*.<domain>`）。
3. 不要在 Cloudflare 给 admin 配置会拦截 `/api` 的 Access 策略；如需保护管理端，另议。

### 3.2 VPN

两端 `wg show` 的 `latest handshake` 应在 2 分钟内，双向 ping `<VPN_HK_IP>` / `<VPN_APP_IP>` 通。

### 3.3 腾讯云安装 Docker

```bash
apt-get update
apt-get install -y docker.io docker-compose
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": ["https://mirror.ccs.tencentyun.com"]
}
EOF
systemctl enable --now docker
systemctl restart docker
docker version
docker compose version
docker info | grep -A3 "Registry Mirrors"
```

说明：腾讯云直连 Docker Hub 会超时，必须用腾讯云内网镜像；GHCR 可直连。
镜像加速只影响 Docker Hub（`postgres`）；app 镜像从 GHCR 拉取。

### 3.4 目录与文件

```bash
mkdir -p /opt/simple-notes/data/media /opt/simple-notes/data/logs /opt/simple-notes/backup
cd /opt/simple-notes

# 方式 A：公开仓库直接 clone（推荐，便于后续 git pull 更新 compose / 脚本）
git clone https://github.com/<owner>/simple-notes.git .

# 方式 B：从本机只传运行所需文件
# scp compose.yaml <APP_HOST>:/opt/simple-notes/
# scp -r deploy scripts <APP_HOST>:/opt/simple-notes/
```

`.env`、`data/`、`backup/` 已被 `.gitignore` 忽略；`git pull` 不会覆盖它们。

### 3.5 生成 `.env`

```bash
cd /opt/simple-notes
bash scripts/init-env.sh
chmod 600 .env
```

编辑 `.env`（不要提交）：

```dotenv
# 必改
ADMIN_ORIGIN=https://<ADMIN_HOST>
APP_BIND=<VPN_APP_IP>          # 只让 VPN 访问 8081
APP_PORT=8081
APP_IMAGE=<APP_IMAGE>

# 其余保留 init-env.sh 生成值：POSTGRES_PASSWORD / JWT_SECRET / ADMIN_INITIAL_PASSWORD
# SIMPLE_NOTES_PORT / WEB_IMAGE 在本拓扑不使用
```

### 3.6 首次启动

```bash
cd /opt/simple-notes
docker compose -f compose.yaml -f deploy/compose.selfhosted.yaml pull db app
docker compose -f compose.yaml -f deploy/compose.selfhosted.yaml up -d db app
docker compose -f compose.yaml -f deploy/compose.selfhosted.yaml ps
docker compose -f compose.yaml -f deploy/compose.selfhosted.yaml logs -f app
```

验证（腾讯云本机）：

```bash
curl -s http://<VPN_APP_IP>:8081/api/version
# → {"version":"0.1.0-rc.1","commit":"…","buildTime":"…","contract":"v1"}
```

首次启动会：

- 创建空数据库并执行 Flyway V1 → V20（含 `root → admin` 改名）；
- 用 `ADMIN_INITIAL_PASSWORD` 覆盖 `admin` 的默认口令；
- 以 `admin` 登录后请立即在个人中心改密。

### 3.7 香港 nginx

构建 admin（本机执行；注意 base 与博客地址）：

```bash
cd frontend
pnpm install

# bash
VITE_ADMIN_BASE=/ VITE_BLOG_URL=https://<BLOG_HOST> RELEASE_VERSION=0.1.0-rc.1 pnpm build:admin

# PowerShell
$env:VITE_ADMIN_BASE='/'; $env:VITE_BLOG_URL='https://<BLOG_HOST>'; $env:RELEASE_VERSION='0.1.0-rc.1'; pnpm build:admin
```

上传并替换旧产物：

```bash
ssh root@<HK_HOST> "rm -rf /var/www/simple-notes/admin && mkdir -p /var/www/simple-notes/admin"
scp -r frontend/apps/admin/dist/* root@<HK_HOST>:/var/www/simple-notes/admin/
```

安装 nginx 配置：以 `deploy/nginx/subdomains.conf.example` 为模板，替换占位符后放到 `/etc/nginx/conf.d/simple-notes.conf`：

```bash
ssh root@<HK_HOST> "nginx -t && systemctl reload nginx"
```

> 注意：admin 的 `frame-src` 必须包含 `https://<BLOG_HOST>`；app 的 `ADMIN_ORIGIN` 必须是 `https://<ADMIN_HOST>`，博客壳靠它放行预览 iframe。

## 4. 发布迭代（每次发版照此执行）

### 4.1 本机：测试与提交

```bash
# 仓库根
git status --short              # 发布提交前必须干净
cd frontend && pnpm typecheck && cd ..
cd backend && ./mvnw test && cd ..
cd desktop && go test ./... && cd ..
```

### 4.2 版本元数据、tag、镜像

```bash
node scripts/release-version.mjs vX.Y.Z-rc.N --apply
git add -A
git commit -m "chore(release): vX.Y.Z-rc.N 元数据"
git tag -a vX.Y.Z-rc.N -m "vX.Y.Z-rc.N"
git push origin main --tags
```

- tag 必须打在最终发布提交上；tag 不可移动 / 覆盖。
- 推送 tag 后 `.github/workflows/images.yml` 构建并推送：
  `ghcr.io/<owner>/simple-notes-app:<version>` / `simple-notes-web:<version>`。
- 等待 Actions 成功；若 GHCR 包为私有，在服务器执行 `docker login ghcr.io`（只读 PAT），不要提交凭据。

### 4.3 腾讯云：更新 app

```bash
cd /opt/simple-notes
bash scripts/docker-backup.sh /opt/simple-notes/backup/$(date +%Y%m%d-%H%M%S)

# 修改 .env 的 APP_IMAGE 为新版本
docker compose -f compose.yaml -f deploy/compose.selfhosted.yaml pull app
docker compose -f compose.yaml -f deploy/compose.selfhosted.yaml up -d app
docker compose -f compose.yaml -f deploy/compose.selfhosted.yaml logs --tail=100 app
```

- Flyway 在 app 启动时自动迁移；只能前进，升级前必须备份。
- 不要 `docker compose down -v`，会删除数据库卷。

### 4.4 香港：更新 admin 静态

```bash
cd frontend
VITE_ADMIN_BASE=/ VITE_BLOG_URL=https://<BLOG_HOST> RELEASE_VERSION=<version> pnpm build:admin
ssh root@<HK_HOST> "rm -rf /var/www/simple-notes/admin && mkdir -p /var/www/simple-notes/admin"
scp -r apps/admin/dist/* root@<HK_HOST>:/var/www/simple-notes/admin/
```

### 4.5 验证

```bash
curl -s https://<ADMIN_HOST>/api/version   # version = 本次 tag
curl -sI https://<ADMIN_HOST>/             # 200，HTML
curl -sI https://<BLOG_HOST>/              # 200，博客壳
```

人工检查：

- [ ] `admin` 能登录，账户菜单显示管理端 / 服务端版本一致
- [ ] 博客首页、文章详情、标签 / 归档 / 搜索正常
- [ ] 主题预览 iframe 能加载（无 CSP 报错）
- [ ] 上传一张图片，admin 与博客都能显示
- [ ] 主题 AI 对话能流式返回（注意 Cloudflare 100s 超时边界）
- [ ] `/opt/simple-notes/data/media` 出现文件，日志正常滚动

## 5. 备份与恢复

```bash
cd /opt/simple-notes
bash scripts/docker-backup.sh /opt/simple-notes/backup/$(date +%Y%m%d-%H%M%S)
```

产物：`db.dump` + `media.tgz` + `.env`（含密钥，权限 600）。建议 cron 每日一次、保留 14 天：

```cron
30 3 * * * cd /opt/simple-notes && bash scripts/docker-backup.sh /opt/simple-notes/backup/$(date +\%Y\%m\%d-\%H\%M\%S) >> /opt/simple-notes/backup/backup.log 2>&1
0 4 * * * find /opt/simple-notes/backup -mindepth 1 -maxdepth 1 -type d -mtime +14 -exec rm -rf {} +
```

恢复（app 会先停）：

```bash
cd /opt/simple-notes
IMAGE="$(grep '^APP_IMAGE=' .env | cut -d= -f2-)" START_SERVICES=app \
  bash scripts/docker-restore.sh /opt/simple-notes/backup/<时间戳>
```

> 备份目录里的 `.env` 含 JWT / DB / 初始口令，不要同步到公开位置；需要异地备份时先加密。

## 6. 回滚

- **镜像回滚**：把 `.env` 的 `APP_IMAGE` 改为上一个 tag → `pull app` → `up -d app`。
- **admin 回滚**：用上一个 tag 重新构建 admin 并覆盖 `/var/www/simple-notes/admin`。
- **数据库**：Flyway 不支持回退；只能恢复升级前备份。因此 4.3 的备份不能省。
- **不要**为了回滚执行 `docker compose down -v` 或删除 `simple-notes-pg` 卷。

## 7. 资源预算

腾讯云 4 GiB 内存、宿主机还有其他服务时的默认值（`deploy/compose.selfhosted.yaml`）：

| 服务 | 限制 | 说明 |
| --- | --- | --- |
| app | `mem_limit: 832m`，`-Xmx448m` | Spring Boot + JPA + Caffeine + springdoc |
| db | `mem_limit: 320m` | `shared_buffers=128MB`、`max_connections=30` |
| 合计 | ≈ 1.15 GiB | 留 200–300 MiB 给系统 / Docker |

观察：

```bash
free -m
docker stats --no-stream
df -h /var/lib/docker /opt/simple-notes
```

- 若频繁 OOM / 重启：先停掉宿主机上不必要的进程，或升级到 8 GiB；再考虑上调 `mem_limit` 与 `-Xmx`。
- 香港只放静态文件，不需要 Docker；不要在香港构建镜像。

## 8. 故障排查

| 现象 | 检查 |
| --- | --- |
| 502 / 504（admin 或 blog） | `ping <VPN_APP_IP>`；腾讯云 `docker compose ps`；app 是否绑定在 `<VPN_APP_IP>:8081`；`docker compose logs app` |
| 版本号不对 | admin 构建时的 `RELEASE_VERSION`；app 的 `APP_IMAGE`；`/api/version` |
| 登录 429 | 应用按“用户名 + 客户端 IP”锁 60 秒；nginx `sn_login` 限流；确认 `X-Forwarded-For` 是真实 IP |
| 预览 iframe 空白 | admin CSP 的 `frame-src` 是否含 `https://<BLOG_HOST>`；app 的 `ADMIN_ORIGIN` 是否为 `https://<ADMIN_HOST>` |
| 上传失败 | nginx `client_max_body_size 25m`；`./data/media` 权限；app 容器日志 |
| 媒体 404 | `data/media` 挂载；数据库 `attachment.storage_mode`；`/api/media/{id}` 是否经反代 |
| 博客静态资源 404 | `blog` 子域是否把所有路径反代到 app；不要只代理 `/blog/` |
| Cloudflare 52x | 源站 nginx 是否 443 正常；Cloudflare SSL 模式是否 Full (strict) |
| 数据库启动失败 | `docker compose logs db`；卷是否被误删；磁盘空间 |

## 9. 安全边界

- app 端口只绑 `<VPN_APP_IP>`，数据库不映射宿主机端口；公网只开 80/443（香港）。
- nginx 覆盖写 `X-Forwarded-For $remote_addr`；app 只信任来自 VPN 的请求（`server.forward-headers-strategy=framework`）。
- `.env` 600；备份含密钥也按 600 保存；仓库和 `.local/` 之外不落任何口令。
- 服务器真实 IP / 密钥路径只写 `.local/deploy.md`（不提交）。
- 首个版本上线后立即修改 `admin` 初始口令；`ADMIN_INITIAL_PASSWORD` 只用于首次启动覆盖默认口令。
