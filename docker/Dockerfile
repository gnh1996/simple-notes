# syntax=docker/dockerfile:1.7

# ---------------------------------------------------------------------------
# 3 容器架构：
#   web  : nginx（admin 静态 + 反代）
#   app  : Spring Boot（jar，含 blog runtime）
#   db   : postgres:17（官方镜像，见 compose.yaml）
#
#   docker build --target app -t simple-notes-app:local .
#   docker build --target web -t simple-notes-web:local .
#
# arm64：加 --platform linux/arm64；前端 / 后端 / 运行镜像均有多架构基础镜像。
# ---------------------------------------------------------------------------

ARG NODE_VERSION=24
ARG RELEASE_VERSION=0.0.0-dev

# 1) 前端：admin 静态（web 镜像用） + blog runtime（随 jar 发布）
FROM node:${NODE_VERSION}-bookworm-slim AS frontend
ARG RELEASE_VERSION
ENV RELEASE_VERSION=${RELEASE_VERSION} \
    VITE_ADMIN_BASE=/admin/
WORKDIR /src/frontend
# 不用 corepack：它内部用 Node fetch，不读取 HTTP(S)_PROXY（受限网络/CI 会失败）
RUN npm install -g pnpm@11.14.0
COPY frontend/package.json frontend/pnpm-workspace.yaml frontend/pnpm-lock.yaml frontend/tsconfig.base.json ./
COPY frontend/apps ./apps
COPY frontend/packages ./packages
COPY frontend/scripts ./scripts
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile
# theme-contract --emit 会校验 docs/theme-contract.md 并写回 backend / docs；镜像只带规范文档
RUN mkdir -p /src/docs
COPY docs/theme-contract.md docs/theme-contract.generated.md /src/docs/
RUN pnpm build:admin && pnpm build:blog

# 2) 后端：jar（blog runtime 会在 prepare-package 阶段进入 static/blog）
FROM eclipse-temurin:25-jdk AS backend
ARG RELEASE_VERSION
# 受限网络：Maven Wrapper 的 Downloader 走 Java，不读 HTTP(S)_PROXY；可用该 build-arg 注入
ARG JAVA_TOOL_OPTIONS=""
ENV JAVA_TOOL_OPTIONS=${JAVA_TOOL_OPTIONS}
WORKDIR /src/backend
COPY backend/mvnw backend/pom.xml ./
COPY backend/.mvn .mvn
RUN chmod +x mvnw
COPY backend/src src
COPY --from=frontend /src/frontend/apps/blog/dist /src/frontend/apps/blog/dist
COPY --from=frontend /src/backend/src/main/resources/theme/theme-contract.json \
     /src/backend/src/main/resources/theme/theme-contract.json
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=secret,id=maven_settings,target=/tmp/maven-settings.xml,required=false \
    unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy; \
    if [ -f /tmp/maven-settings.xml ]; then SETTINGS="-s /tmp/maven-settings.xml"; else SETTINGS=""; fi; \
    ./mvnw -B -ntp $SETTINGS -DskipTests -Drevision=${RELEASE_VERSION} package

FROM backend AS artifact
RUN set -eux; \
    jar="$(find target -maxdepth 1 -name 'simple-notes-*.jar' ! -name '*.original' | head -n1)"; \
    test -n "$jar"; \
    cp "$jar" /app.jar

# 3) 应用镜像：API + blog（8081），由 web / 外部反代转发
FROM eclipse-temurin:25-jre AS app
ARG RELEASE_VERSION
LABEL org.opencontainers.image.title="Simple Notes (app)" \
      org.opencontainers.image.version="${RELEASE_VERSION}"

RUN set -eux; \
    groupadd --gid 10001 simplenotes; \
    useradd --uid 10001 --gid simplenotes --home-dir /app --shell /usr/sbin/nologin simplenotes; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl; \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=artifact /app.jar /app/app.jar
COPY docker/app-entrypoint.sh /usr/local/bin/app-entrypoint.sh
RUN chmod +x /usr/local/bin/app-entrypoint.sh
# 供裸机 nginx / 其他静态托管取用；app 本身不托管 admin
COPY --from=frontend /src/frontend/apps/admin/dist /opt/simple-notes/admin

ENV SERVER_PORT=8081 \
    MEDIA_LOCAL_ROOT=/data/media \
    LOG_FILE=/data/logs/simple-notes.log \
    STORAGE_MODE=LOCAL
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8081/api/version >/dev/null || exit 1
ENTRYPOINT ["/usr/local/bin/app-entrypoint.sh"]

# 4) Web 镜像：admin 静态 + /api /blog / 反代
FROM nginx:1.27-alpine AS web
ARG RELEASE_VERSION
LABEL org.opencontainers.image.title="Simple Notes (web)" \
      org.opencontainers.image.version="${RELEASE_VERSION}"
COPY docker/nginx.conf /etc/nginx/nginx.conf
COPY docker/proxy-app.conf docker/security-headers.conf /etc/nginx/
COPY --from=frontend /src/frontend/apps/admin/dist /usr/share/nginx/html/admin
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
