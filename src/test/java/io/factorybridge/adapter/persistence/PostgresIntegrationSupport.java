package io.factorybridge.adapter.persistence;

import io.factorybridge.application.model.Acceptance;
import io.factorybridge.application.port.DeliveryStore;
import io.factorybridge.application.port.MeasurementStore;
import io.factorybridge.application.port.StagingStore;
import io.factorybridge.application.port.WarehouseWriter;
import io.factorybridge.domain.CanonicalMeasurement;
import io.factorybridge.domain.MetricType;
import io.factorybridge.domain.QualityStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(properties = "factorybridge.delivery.enabled=false")
abstract class PostgresIntegrationSupport {
    protected static final Instant NOW = Instant.parse("2026-09-10T06:30:22Z");
    protected static final String HASH = "a".repeat(64);
    protected static final String CORRELATION_ID = "persistence-integration-test";

    // 測試 JVM 共用一個真正的 PostgreSQL；Ryuk 負責 JVM 結束時清理。
    // Docker 不可用時直接失敗，避免沒有驗證 DB 卻回報成功。
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected StagingStore staging;
    @Autowired protected MeasurementStore measurements;
    @Autowired protected DeliveryStore deliveries;
    @Autowired protected WarehouseWriter warehouse;
    @Autowired protected PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearPipelineData() {
        jdbc.execute(
                """
                TRUNCATE TABLE factorybridge.staging_record, factorybridge.delivery_outbox,
                    factorybridge.measurement, warehouse.fact_measurement
                """);
    }

    protected UUID receiveRawPayload() {
        return staging.recordReceived("{\"sourceSystem\":\"MES_A\"}", CORRELATION_ID, NOW);
    }

    protected Acceptance accept(String sourceRecordId, QualityStatus quality) {
        return measurements.acceptMeasurement(
                receiveRawPayload(),
                measurement(sourceRecordId, quality),
                HASH,
                CORRELATION_ID,
                NOW);
    }

    protected CanonicalMeasurement measurement(String sourceRecordId, QualityStatus quality) {
        return new CanonicalMeasurement(
                "EQ-CT-003",
                "COATING_MACHINE",
                "KH01",
                "CELL-LINE-01",
                "COATING-03",
                "LOT-20260910-0012",
                "BATCH-00192",
                MetricType.TEMPERATURE,
                new BigDecimal("35.200000"),
                "C",
                NOW,
                quality,
                "MES_A",
                sourceRecordId);
    }

    protected long canonicalCount() {
        return jdbc.queryForObject("SELECT count(*) FROM factorybridge.measurement", Long.class);
    }

    protected long outboxCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM factorybridge.delivery_outbox", Long.class);
    }
}
