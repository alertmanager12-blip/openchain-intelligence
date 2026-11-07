# syntax=docker/dockerfile:1.7
# OpenChain Intelligence — backend image (build context: repository root)
# Stage 1: build with Maven on JDK 21
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# POMs first for better layer caching
COPY pom.xml ./
COPY oci-core/pom.xml oci-core/
COPY oci-blockchain/pom.xml oci-blockchain/
COPY oci-intelligence/pom.xml oci-intelligence/
COPY oci-alerting/pom.xml oci-alerting/
COPY oci-app/pom.xml oci-app/
COPY oci-core/src oci-core/src
COPY oci-blockchain/src oci-blockchain/src
COPY oci-intelligence/src oci-intelligence/src
COPY oci-alerting/src oci-alerting/src
COPY oci-app/src oci-app/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl oci-app -am package -DskipTests \
 && jar=$(find oci-app/target -maxdepth 1 -name 'oci-app-*.jar' ! -name '*-plain.jar' ! -name '*-sources.jar' | head -n 1) \
 && test -n "$jar" && cp "$jar" /app.jar

# Stage 2: minimal runtime, non-root. Java 25 runtime (code compiled for Java 21): virtual threads no longer pin
# their carrier inside synchronized blocks (JEP 491, Java 24+), which the indexers rely on.
FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.title="openchain-intelligence" \
      org.opencontainers.image.description="Open source blockchain investigation, risk intelligence and programmable monitoring" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/openchain-intelligence/openchain-intelligence"
RUN groupadd --system --gid 10001 oci \
 && useradd --system --uid 10001 --gid oci --home-dir /app --shell /usr/sbin/nologin oci
WORKDIR /app
COPY --from=build --chown=oci:oci /app.jar /app/app.jar
# Community rule pack (read-only): resolved as `rules` relative to WORKDIR by oci.rules.directories.
# Without it the container started with 0 rules and never raised an alert. Override with OCI_RULES_DIRS + a volume.
COPY --chown=oci:oci rules /app/rules
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom" \
    SERVER_PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -q "\"status\":\"UP\"" <&3'
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
