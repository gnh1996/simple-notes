#!/usr/bin/env bash
# app 容器入口：
# 1) 准备 /data 挂载目录权限（容器以 root 启动，随后降权到 simplenotes）
# 2) 等待数据库可达（本地 db 服务或外部 DB_URL）
# 3) 以 simplenotes 用户启动 Spring Boot
set -euo pipefail

: "${JWT_SECRET:?缺少 JWT_SECRET（请在 .env 中设置）}"

DB_URL="${DB_URL:-jdbc:postgresql://db:5432/simple_notes}"
DB_USERNAME="${DB_USERNAME:-simple_notes}"
DB_PASSWORD="${DB_PASSWORD:-${POSTGRES_PASSWORD:-}}"
if [ -z "$DB_PASSWORD" ]; then
  echo "缺少 DB_PASSWORD / POSTGRES_PASSWORD" >&2
  exit 1
fi
export DB_URL DB_USERNAME DB_PASSWORD

mkdir -p /data/media /data/logs
chown simplenotes:simplenotes /data/media /data/logs

# 从 jdbc:postgresql://host:port/db 中取 host/port；无端口默认 5432
hostport="${DB_URL#jdbc:postgresql://}"
hostport="${hostport%%/*}"
host="${hostport%:*}"
if [ "$hostport" = "$host" ]; then
  port=5432
else
  port="${hostport##*:}"
fi

echo "等待数据库 ${host}:${port} …"
i=0
until timeout 2 bash -c "</dev/tcp/${host}/${port}" 2>/dev/null; do
  i=$((i + 1))
  if [ "$i" -ge 120 ]; then
    echo "数据库 ${host}:${port} 未就绪，退出等待" >&2
    exit 1
  fi
  sleep 1
done

export HOME=/app
# setpriv 会 exec 目标进程，PID 1 直接变成 java：docker stop 的 SIGTERM 不会被中间层吞掉
exec setpriv --reuid=10001 --regid=10001 --clear-groups \
  java -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError \
       -Dserver.shutdown=graceful \
       -Dspring.lifecycle.timeout-per-shutdown-phase=20s \
       -jar /app/app.jar
