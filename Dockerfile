# syntax=docker/dockerfile:1.6
# ── Build stage ───────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

COPY pom.xml .

# BuildKit cache mount: reuse ~/.m2 across builds so dependencies are not
# re-downloaded on every CI run. Cache is local to the builder, not in the image.
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -q

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -q

# Download New Relic Java agent — cached between builds by BuildKit.
# Placed at /opt/newrelic/newrelic.jar to match JDK_JAVA_OPTIONS in config.env.
ARG NR_AGENT_VERSION=8.9.0
RUN --mount=type=cache,target=/tmp/nr-cache \
    set -e; \
    CACHED="/tmp/nr-cache/newrelic-${NR_AGENT_VERSION}.jar"; \
    if [ ! -f "$CACHED" ]; then \
        wget -q "https://download.newrelic.com/newrelic/java-agent/newrelic-agent/${NR_AGENT_VERSION}/newrelic-java.zip" \
             -O /tmp/nr.zip \
        && unzip -q /tmp/nr.zip -d /tmp/nr-unzip \
        && cp /tmp/nr-unzip/newrelic/newrelic.jar "$CACHED" \
        && rm -rf /tmp/nr.zip /tmp/nr-unzip; \
    fi; \
    mkdir -p /opt/newrelic && cp "$CACHED" /opt/newrelic/newrelic.jar

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user — least privilege
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=build /app/target/fibonacci-1.0.0.jar app.jar
COPY --from=build /opt/newrelic /opt/newrelic

USER appuser

EXPOSE 8080

# HEALTHCHECK for docker-compose and standalone docker run.
# Kubernetes uses its own probes from probes.yaml — this is the Docker-native equivalent.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# JDK_JAVA_OPTIONS injected from Kubernetes ConfigMap (config.env) activates NR agent:
#   -javaagent:/opt/newrelic/newrelic.jar ...
# newrelic.yml is mounted at /newrelic/newrelic.yml by the deployment volume.
# NEW_RELIC_LICENSE_KEY comes from the fibonacci-secret ExternalSecret.
ENTRYPOINT ["java", "-jar", "app.jar"]
