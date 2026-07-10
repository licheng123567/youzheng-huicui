# 有证慧催 后端镜像（多阶段）
# 构建上下文 = 仓库根目录：  docker build -f deploy/Dockerfile -t huicui-backend .
#
# 关键点：所有 Flyway 迁移都在 backend/app/src/main/resources/db/migration，
# 会被打进 jar（classpath:db/migration）。**不要**再引入 filesystem 相对路径——
# 历史事故：jar 部署时相对路径不存在，fail-on-missing-locations=false 会静默跳过核心 schema。

# ── 构建阶段 ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# 依赖缓存用 BuildKit 的 maven repo 挂载，而不是 `dependency:go-offline`
# ——后者在冷缓存下极慢且容易挂死（实测 25min 无输出），收益只有层缓存，不值当。
COPY backend/app/pom.xml backend/app/pom.xml
COPY backend/app/src backend/app/src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -f backend/app/pom.xml -B -DskipTests package \
 && cp backend/app/target/huicui-app-*.jar /src/app.jar

# ── 运行阶段 ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# 构建溯源：由 .github/workflows/release.yml 注入。
# 既写成 OCI label（docker inspect 可查），又注入环境变量（/actuator/info 可查）——
# 出事时运维手上未必有 docker 权限，但一定能打 HTTP。
ARG HUICUI_VERSION=unknown
ARG HUICUI_REVISION=unknown
LABEL org.opencontainers.image.title="有证慧催 后端" \
      org.opencontainers.image.source="https://github.com/licheng123567/youzheng-huicui" \
      org.opencontainers.image.version="${HUICUI_VERSION}" \
      org.opencontainers.image.revision="${HUICUI_REVISION}"
ENV HUICUI_VERSION=${HUICUI_VERSION} \
    HUICUI_REVISION=${HUICUI_REVISION}

# curl 供 HEALTHCHECK 用（temurin jre 镜像不自带）；非 root 运行
RUN apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -r huicui && useradd -r -g huicui huicui
COPY --from=build /src/app.jar /app/app.jar
USER huicui

EXPOSE 9091

# 不在镜像里写死 profile：基础 yml 默认就是 prod（fail-closed）。
# 缺少 SPRING_DATASOURCE_URL / HUICUI_JWT_SECRET 时 ProdGuard 会拒绝启动，这是期望行为。
# 容器内存感知：让 JVM 按 cgroup 限额取堆上限。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=15s --timeout=3s --start-period=90s --retries=5 \
  CMD ["sh", "-c", "curl -fsS http://localhost:9091/v1/actuator/health/readiness || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
