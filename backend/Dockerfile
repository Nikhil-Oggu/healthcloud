# HealthCloud backend — production container image (Phase 10, slice 1).
#
# A multi-stage build: stage 1 compiles + packages the Spring Boot jar with a full JDK;
# stage 2 ships only the jar on a slim JRE, as a non-root user. This is the artifact AWS
# ECS Fargate will run in a later slice — locally it is verified by `docker build` + a
# `/actuator/health` probe (see backend/README.md).
#
# Build:  docker build -t healthcloud-backend:local ./backend
# Run:    docker compose --profile full up -d postgres backend   (from the repo root)

# ---- Stage 1: build ---------------------------------------------------------------------
# Full JDK 25 (Temurin) — matches the project's frozen Java 25 baseline (CLAUDE.md §29).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy the Maven wrapper + POM FIRST and pre-fetch dependencies. Because these change far less
# often than source, Docker caches this layer and a source-only change rebuilds in seconds
# instead of re-downloading the whole dependency tree.
COPY mvnw ./
COPY .mvn ./.mvn
COPY pom.xml ./
RUN chmod +x mvnw && ./mvnw -B --no-transfer-progress dependency:go-offline

# Now copy the source and package the executable jar.
# Tests are SKIPPED here on purpose: the suite uses Testcontainers (it starts a real Postgres
# via Docker), which does not belong inside an image build — tests already gate every change in
# CI (.github/workflows/ci.yml). This stage's job is to package, not to re-test.
COPY src ./src
RUN ./mvnw -B --no-transfer-progress clean package -DskipTests

# ---- Stage 2: runtime -------------------------------------------------------------------
# Slim JRE (no compiler/build tools) → smaller image and a smaller attack surface.
FROM eclipse-temurin:25-jre AS runtime

# curl backs the container HEALTHCHECK below (and is the same probe AWS's load balancer will
# use). Installed in one layer, apt lists cleaned up to keep the image small.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Run as an unprivileged user, never root (defense in depth: a compromised process cannot
# write outside its own files or escalate as easily).
RUN groupadd --system spring && useradd --system --gid spring --home /app spring
WORKDIR /app

# Copy just the packaged jar from the build stage. The Spring Boot repackage produces exactly
# one executable jar in target/ (the *.jar.original is not matched).
COPY --from=build /workspace/target/*.jar app.jar
RUN chown -R spring:spring /app
USER spring

EXPOSE 8080

# JAVA_OPTS is an env hook so memory/GC flags can be tuned per environment (e.g. ECS task
# definition) without rebuilding the image. Modern JVMs are container-memory-aware by default.
ENV JAVA_OPTS=""

# Report container health from the app's own actuator endpoint — the same signal ECS/ALB use.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# Shell form so ${JAVA_OPTS} is expanded; exec so the JVM is PID 1 and receives stop signals.
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar app.jar"]
