#!/usr/bin/env bash
# 构建镜像：默认同时构建 app + web。
#
# 用法：bash scripts/docker-build.sh [app|web|all] [tag]
#   all  （默认）→ simple-notes-app:local + simple-notes-web:local
#   app           → simple-notes-app:local（可传第二个参数覆盖 tag）
#   web           → simple-notes-web:local
#
# 可选环境变量：
#   RELEASE_VERSION      默认 0.0.0-dev
#   HTTP_PROXY/HTTPS_PROXY  宿主机代理（自动转为 build-arg 与 Maven settings）
#   MAVEN_SETTINGS_FILE  直接指定 Maven settings.xml（优先于自动生成）
set -euo pipefail
cd "$(dirname "$0")/.."

TARGET="${1:-all}"
TAG="${2:-}"
case "$TARGET" in
  app) TARGETS=(app); DEFAULT_TAG=simple-notes-app:local ;;
  web) TARGETS=(web); DEFAULT_TAG=simple-notes-web:local ;;
  all) TARGETS=(app web) ;;
  *) echo "target 只能是 app / web / all" >&2; exit 2 ;;
esac

COMMON_ARGS=(--build-arg "RELEASE_VERSION=${RELEASE_VERSION:-0.0.0-dev}")

PROXY="${HTTP_PROXY:-${HTTPS_PROXY:-${http_proxy:-${https_proxy:-}}}}"
if [ -n "$PROXY" ]; then
  COMMON_ARGS+=(--build-arg "HTTP_PROXY=$PROXY" --build-arg "HTTPS_PROXY=$PROXY")
  COMMON_ARGS+=(--build-arg "NO_PROXY=${NO_PROXY:-localhost,127.0.0.1}")

  rest="${PROXY#*://}"
  hostport="${rest%%/*}"
  if [[ "$hostport" == *:* ]]; then
    host="${hostport%:*}"
    port="${hostport##*:}"
  else
    host="$hostport"
    port=3128
  fi
  COMMON_ARGS+=(--build-arg "JAVA_TOOL_OPTIONS=-Dhttps.proxyHost=$host -Dhttps.proxyPort=$port -Dhttp.proxyHost=$host -Dhttp.proxyPort=$port")
  if [ "$host" = "127.0.0.1" ] || [ "$host" = "localhost" ]; then
    COMMON_ARGS+=(--network host)
  fi
fi

if [ -n "${MAVEN_SETTINGS_FILE:-}" ]; then
  COMMON_ARGS+=(--secret "id=maven_settings,src=$MAVEN_SETTINGS_FILE")
elif [ -n "$PROXY" ]; then
  TMP_SETTINGS="$(mktemp)"
  trap 'rm -f "$TMP_SETTINGS"' EXIT
  cat > "$TMP_SETTINGS" <<EOF
<settings>
  <proxies>
    <proxy>
      <id>docker-build-proxy</id>
      <active>true</active>
      <protocol>http</protocol>
      <host>$host</host>
      <port>$port</port>
    </proxy>
  </proxies>
</settings>
EOF
  COMMON_ARGS+=(--secret "id=maven_settings,src=$TMP_SETTINGS")
fi

for t in "${TARGETS[@]}"; do
  if [ "$TARGET" = "all" ]; then
    case "$t" in
      app) image=simple-notes-app:local ;;
      web) image=simple-notes-web:local ;;
    esac
  else
    image="${TAG:-$DEFAULT_TAG}"
  fi
  docker build --target "$t" -t "$image" -f docker/Dockerfile "${COMMON_ARGS[@]}" .
  echo "已构建 $image（target=$t）"
done
