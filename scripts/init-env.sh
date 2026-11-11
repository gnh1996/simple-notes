#!/usr/bin/env bash
# 生成 .env（首次部署）：随机数据库口令、JWT 密钥、root 初始口令。
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f .env ]; then
  echo ".env 已存在，未覆盖。如需重新生成请先备份并删除。" >&2
  exit 1
fi

umask 077
POSTGRES_PASSWORD="$(openssl rand -hex 24)"
JWT_SECRET="$(openssl rand -base64 48 | tr -d '\n')"
ROOT_INITIAL_PASSWORD="$(openssl rand -hex 16)"

cat > .env <<EOF
POSTGRES_DB=simple_notes
POSTGRES_USER=simple_notes
POSTGRES_PASSWORD=$POSTGRES_PASSWORD
JWT_SECRET=$JWT_SECRET
ROOT_INITIAL_PASSWORD=$ROOT_INITIAL_PASSWORD
SIMPLE_NOTES_PORT=8080
ADMIN_ORIGIN=http://localhost:8080
EOF
chmod 600 .env

echo "已生成 .env"
echo "root 初始口令：$ROOT_INITIAL_PASSWORD"
echo
echo "下一步：docker compose up -d --build"
echo "外部数据库：编辑 .env 的 DB_URL / DB_USERNAME / DB_PASSWORD，然后"
echo "  docker compose up -d --no-deps app web"
echo "裸机 nginx：只启动 db + app，再按 deploy/nginx/site.conf.example 配置"
