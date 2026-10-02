# Multi-stage build for the DTC scheduling service.
#
# Two stages for two reasons. The build stage carries the JDK, Maven and the whole dependency cache, which is
# roughly 700 MB of things that have no business being on a production host. The runtime stage carries a JRE and
# the application, and nothing that could compile or download code.

# ---------------------------------------------------------------------------
# Stage 1 — build
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first, as their own layer. The POM changes far less often than the source, so a code-only change
# reuses the cached dependency layer instead of re-downloading everything.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src

# Tests are not run here. They need a Docker daemon for Testcontainers, and a build that starts containers
# inside a container image build is a problem nobody should have to debug. CI runs the test suite separately;
# this stage only produces the artefact.
RUN mvn -B -q clean package -DskipTests

# Unpack the layered jar. Spring Boot orders the layers by how often they change, so a redeploy of changed
# application code ships a few hundred kilobytes rather than the whole fat jar.
RUN java -Djarmode=layertools -jar target/*.jar extract --destination /build/layers

# ---------------------------------------------------------------------------
# Stage 2 — runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# A non-root user, created before anything is copied in. Running as root inside a container means a container
# escape starts with root on the host, and nothing this application does needs privilege.
RUN addgroup -S -g 10001 dtc \
 && adduser -S -u 10001 -G dtc -h /app -s /sbin/nologin dtc

# curl for the container healthcheck below. The JRE image has no shell utilities for HTTP.
RUN apk add --no-cache curl tzdata \
 && ln -sf /usr/share/zoneinfo/Asia/Kolkata /etc/localtime

WORKDIR /app

# Layer order matters: least-likely to change first, so Docker reuses the most layers on a redeploy.
COPY --from=build --chown=dtc:dtc /build/layers/dependencies/ ./
COPY --from=build --chown=dtc:dtc /build/layers/spring-boot-loader/ ./
COPY --from=build --chown=dtc:dtc /build/layers/snapshot-dependencies/ ./
COPY --from=build --chown=dtc:dtc /build/layers/application/ ./

USER dtc:dtc
EXPOSE 8080

# Container-aware heap sizing rather than a fixed -Xmx. A hard-coded heap either wastes the memory limit or
# exceeds it and gets the container killed; a percentage adapts to whatever limit the orchestrator sets.
#
# ExitOnOutOfMemoryError because a JVM that has run out of heap is not going to recover, and a pod that exits is
# restarted by the orchestrator while one that limps along keeps failing requests.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=50 -XX:+ExitOnOutOfMemoryError \
-XX:+UseContainerSupport -Djava.security.egd=file:/dev/./urandom -Duser.timezone=Asia/Kolkata"

ENV SPRING_PROFILES_ACTIVE=prod

# The readiness probe, so `docker run` alone reports health. Kubernetes uses its own probes and ignores this.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

# exec form, so the JVM is PID 1 and receives SIGTERM directly. Without it a shell is PID 1, the signal never
# reaches the JVM, and every deployment waits for the grace period to expire before being killed.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
