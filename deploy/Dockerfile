# syntax=docker/dockerfile:1
# Lagoon hub image: the web client (Node build) served by the hub (Kotlin/JVM).
# Build from the repository root: docker build -f deploy/Dockerfile -t lagoon-hub .

FROM node:22-alpine AS web
WORKDIR /src/web
RUN corepack enable
COPY web/package.json web/pnpm-lock.yaml web/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY web/ ./
COPY contract/ /src/contract/
ARG LAGOON_VERSION=dev
RUN pnpm build

FROM eclipse-temurin:17-jdk-noble AS hub
WORKDIR /src
# No Android SDK here: only the hub project is configured.
ENV LAGOON_HUB_ONLY=1 BUILD_SCRATCH_ROOT=/build GRADLE_USER_HOME=/gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY shared/ shared/
COPY hub/ hub/
RUN --mount=type=cache,target=/gradle ./gradlew --no-daemon --console=plain :hub:installDist \
  && cp -r "$(find /build -type d -path '*/hub/install/lagoon-hub' | head -n 1)" /opt/lagoon-hub

FROM eclipse-temurin:21-jre-noble
ARG LAGOON_VERSION=dev
LABEL org.opencontainers.image.title="Lagoon Hub" \
      org.opencontainers.image.description="Self-hosted hub and web client for OpenCode / DeepSeek Harness" \
      org.opencontainers.image.source="https://github.com/IGNGserver/Lagoon" \
      org.opencontainers.image.version="${LAGOON_VERSION}" \
      org.opencontainers.image.licenses="NOASSERTION"
RUN useradd --system --uid 10001 --home-dir /data --shell /usr/sbin/nologin lagoon \
  && mkdir -p /data && chown lagoon:lagoon /data
COPY --from=hub /opt/lagoon-hub /app/hub
COPY --from=web /src/web/dist /app/web
ENV LAGOON_DATA_DIR=/data LAGOON_WEB_ROOT=/app/web LAGOON_VERSION=${LAGOON_VERSION} \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k"
USER lagoon
VOLUME /data
EXPOSE 25246
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${LAGOON_PORT:-25246} && printf 'GET /healthz HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q ok <&3"]
ENTRYPOINT ["/app/hub/bin/lagoon-hub"]
