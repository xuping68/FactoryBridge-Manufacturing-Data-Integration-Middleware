FROM maven:3.9.15-eclipse-temurin-26 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
# image build 仍執行 unit / wire-contract tests；需 Docker 的 PostgreSQL IT 由 CI verify 執行。
RUN mvn -B -ntp package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system factorybridge \
    && useradd --system --gid factorybridge --no-create-home factorybridge
COPY --from=build --chown=factorybridge:factorybridge /workspace/target/factorybridge-1.0.0.jar /app/factorybridge.jar
USER factorybridge
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 \
    CMD curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/factorybridge.jar"]
