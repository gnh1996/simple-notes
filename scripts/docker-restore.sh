#!/usr/bin/env bash
# 从 docker-backup.sh 的产物恢复（compose 内 db 服务）。
#
# 用法：bash scripts/docker-restore.sh backup/<时间戳>
# 可选环境变量：
#   IMAGE             app 镜像（默认 simple-notes-app:local）
#   DATA_DIR          宿主机媒体目录（默认 ./data）
#   START_SERVICES    恢复后启动的服务（默认 "app web"；裸机 nginx 用 "app"）
set -euo pipefail
cd "$(dirname "$0")/.."

SRC="${1:-}"
if [ -z "$SRC" ] || [ ! -f "$SRC/db.dump" ]; then
  echo "用法：bash scripts/docker-restore.sh backup/<时间戳>（目录内需有 db.dump）" >&2
  exit 1
fi

DB_URL_LINE="$(grep -E '^DB_URL=' .env 2>/dev/null | head -1 | cut -d= -f2- || true)"
if [ -n "${DB_URL_LINE:-}" ]; then
  echo "检测到外部数据库（DB_URL）：本脚本不支持恢复外部库。" >&2
  echo "请对目标库直接执行 pg_restore；media / .env 仍可手动恢复。" >&2
  exit 1
fi

IMAGE="${IMAGE:-simple-notes-app:local}"
DATA_DIR="${DATA_DIR:-./data}"
START_SERVICES="${START_SERVICES:-app web}"
SRC_ABS="$(cd "$SRC" && pwd)"

if [ -f "$SRC/.env" ] && [ ! -f .env ]; then
  echo "恢复 .env（来自备份）…"
  cp "$SRC/.env" .env
  chmod 600 .env
elif [ -f "$SRC/.env" ]; then
  echo "警告：当前 .env 已存在，未覆盖；请确认其 DB / JWT 密钥与备份一致。" >&2
fi

mkdir -p "$DATA_DIR/media" "$DATA_DIR/logs"
DATA_DIR_ABS="$(cd "$DATA_DIR" && pwd)"

echo "停止应用 / web…"
docker compose stop app web >/dev/null 2>&1 || true

echo "启动数据库…"
docker compose up -d db

echo "等待 PostgreSQL…"
ready=0
for _ in $(seq 1 90); do
  if docker compose exec -T db sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 1
done
if [ "$ready" != "1" ]; then
  echo "PostgreSQL 未就绪，恢复中止。" >&2
  exit 1
fi

echo "恢复数据库…"
docker compose exec -T db sh -c \
  'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --exit-on-error' \
  < "$SRC/db.dump"

if [ -f "$SRC/media.tgz" ]; then
  echo "恢复媒体文件…"
  docker run --rm \
    -v "$DATA_DIR_ABS:/data" \
    -v "$SRC_ABS:/backup:ro" \
    --entrypoint /bin/bash \
    "$IMAGE" -c '
      set -e
      tar xzf /backup/media.tgz -C /data
      chown -R simplenotes:simplenotes /data/media /data/logs 2>/dev/null || true
    '
else
  echo "警告：缺少 media.tgz，跳过媒体恢复。" >&2
fi

echo "启动：$START_SERVICES"
# shellcheck disable=SC2086
docker compose up -d $START_SERVICES
echo "恢复完成。查看日志：docker compose logs -f app"
