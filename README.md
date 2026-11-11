# Simple Notes

随手记 + 个人博客，本地优先的桌面端，以及一套可自托管的服务端。

- `backend/`：Spring Boot 4 / Java 25 / PostgreSQL（内容、RBAC、主题、附件、同步 API）
- `frontend/`：Vue 3 + Vite 工作区（管理端、博客 runtime、桌面端 UI）
- `desktop/`：Go + Wails v3 桌面壳（Windows / macOS / Linux）
- `docs/`：设计文档与验收记录（先读 `docs/tech-stack.md`）

## Docker 部署（3 容器：db + app + web）

前提：Docker Engine 24+ / Compose v2+（Windows 建议在 WSL2 内运行）。

```bash
bash scripts/init-env.sh          # 生成 .env（随机 DB 口令 / JWT / root 初始口令）
docker compose up -d --build

# 受限网络 / 代理
# bash scripts/docker-build.sh all
# docker compose up -d --no-build
```

打开：

- 管理端：<http://localhost:8080/admin/>
- 博客：<http://localhost:8080/>

初始口令：

```bash
grep ROOT_INITIAL_PASSWORD .env
```

数据：`./data/media`（上传文件）、`./data/logs`、named volume `simple-notes-pg`（PostgreSQL）。
备份与恢复：

```bash
bash scripts/docker-backup.sh
bash scripts/docker-restore.sh backup/<时间戳>
```

支持直连已有 PostgreSQL（`.env` 的 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`），
也支持 admin 静态产物放到裸机 nginx（blog 仍由 app 提供）。
详见 [`docs/deployment.md`](docs/deployment.md)，构建与发版见 [`docs/release.md`](docs/release.md)。

## 本地开发

```bash
cd frontend && pnpm install && pnpm typecheck
pnpm dev:admin            # 5173
pnpm dev:blog             # 5174（页面走后端 8080）

cd backend && ./mvnw spring-boot:run   # 8080，默认 dev profile
cd desktop && wails3 dev               # 需要图形环境
```

约定与边界见根 `AGENTS.md` 与各端 `AGENTS.md`。
