FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
# image build 仍執行 unit / wire-contract tests；需 Docker 的 PostgreSQL IT 由 CI verify 執行。
RUN mvn -B -ntp package

FROM eclipse-temurin:25-jre-jammy
WORKDIR /app
# Temurin 已提供 curl；直接驗證健康檢查依賴，避免重複安裝造成額外套件來源故障點。
RUN command -v curl \
    && groupadd --system factorybridge \
    && useradd --system --gid factorybridge --no-create-home factorybridge
COPY --from=build --chown=factorybridge:factorybridge /workspace/target/factorybridge-1.0.0.jar /app/factorybridge.jar
USER factorybridge
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 \
    CMD curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/factorybridge.jar"]
