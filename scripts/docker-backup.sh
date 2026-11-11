#!/usr/bin/env bash
# 在线备份（3 容器）：db.dump + media.tgz + .env（含机密）。
#
# 用法：bash scripts/docker-backup.sh [输出目录]
# 默认：backup/<时间戳>/
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="${1:-backup/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"

DB_URL_LINE="$(grep -E '^DB_URL=' .env 2>/dev/null | head -1 | cut -d= -f2- || true)"
if [ -n "${DB_URL_LINE:-}" ]; then
  echo "检测到外部数据库（DB_URL）：本脚本只备份 compose 内的 db 服务。" >&2
  echo "请直接对目标库执行 pg_dump；.env / media 仍可继续手动打包。" >&2
  exit 1
fi

echo "备份数据库 → $OUT/db.dump"
docker compose exec -T db sh -c \
  'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$OUT/db.dump"

echo "备份媒体 → $OUT/media.tgz"
docker compose exec -T app tar czf - -C /data media > "$OUT/media.tgz"

if [ -f .env ]; then
  echo "备份 .env → $OUT/.env（含 JWT / DB 机密）"
  cp .env "$OUT/.env"
  chmod 600 "$OUT/.env"
fi

echo
echo "完成：$OUT"
echo "- db.dump   PostgreSQL 自定义格式"
echo "- media.tgz 上传文件"
echo "- .env      JWT / DB / root 初始口令"
