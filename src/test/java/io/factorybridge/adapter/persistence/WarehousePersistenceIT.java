package io.factorybridge.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.factorybridge.domain.CanonicalMeasurement;
import io.factorybridge.domain.ErrorCode;
import io.factorybridge.domain.FactoryBridgeException;
import io.factorybridge.domain.QualityStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.Instant;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class WarehousePersistenceIT extends PostgresIntegrationSupport {
    @Test
    void newMeasurementCreatesEquipmentDateAndFactWithAnalyticalForeignKeys() {
        UUID measurementId = UUID.randomUUID();
        warehouse.writeMeasurement(
                measurementId, measurement("star-schema", QualityStatus.WARNING));

        var joined =
                jdbc.queryForMap(
                        """
                SELECT f.measurement_id, e.equipment_key, e.equipment_id, e.equipment_type,
                       e.plant_code, e.line_code, e.station_code, d.date_key, d.full_date,
                       d.year, d.quarter, d.month, d.day, f.quality_status
                FROM warehouse.fact_measurement f
                JOIN warehouse.dim_equipment e USING (equipment_key)
                JOIN warehouse.dim_date d USING (date_key)
                """);
        assertThat(joined)
                .containsAllEntriesOf(
                        Map.of(
                                "measurement_id",
                                measurementId,
                                "equipment_id",
                                "EQ-CT-003",
                                "equipment_type",
                                "COATING_MACHINE",
                                "plant_code",
                                "KH01",
                                "line_code",
                                "CELL-LINE-01",
                                "station_code",
                                "COATING-03",
                                "quality_status",
                                "WARNING"));
        assertThat(((Number) joined.get("equipment_key")).longValue()).isPositive();
        assertThat(joined)
                .containsAllEntriesOf(
                        Map.of(
                                "date_key",
                                20260910,
                                "full_date",
                                Date.valueOf("2026-09-10"),
                                "year",
                                2026,
                                "quarter",
                                3,
                                "month",
                                9,
                                "day",
                                10));
        assertThat(equipmentCount()).isEqualTo(1);
        assertThat(dateCount()).isEqualTo(1);
    }

    @Test
    void reusesEquipmentWithinPlantButSeparatesTheSameEquipmentCodeAcrossPlants() {
        warehouse.writeMeasurement(UUID.randomUUID(), measurement("first", QualityStatus.GOOD));
        warehouse.writeMeasurement(UUID.randomUUID(), measurement("same-plant", QualityStatus.BAD));
        warehouse.writeMeasurement(
                UUID.randomUUID(),
                atPlantAndTime("other-plant", "TN01", NOW, QualityStatus.WARNING));

        assertThat(factCount()).isEqualTo(3);
        assertThat(equipmentCount()).isEqualTo(2);
        assertThat(dateCount()).isEqualTo(1);
        assertThat(
                        jdbc.queryForList(
                                """
                SELECT e.plant_code, count(*) AS measurements
                FROM warehouse.fact_measurement f
                JOIN warehouse.dim_equipment e USING (equipment_key)
                GROUP BY e.plant_code ORDER BY e.plant_code
                """))
                .containsExactly(
                        Map.of("plant_code", "KH01", "measurements", 2L),
                        Map.of("plant_code", "TN01", "measurements", 1L));
    }

    @Test
    void equipmentAttributesRemainAtFirstLoadWhenLaterMeasurementsReportAnotherLocation() {
        var first = measurement("first-location", QualityStatus.GOOD);
        warehouse.writeMeasurement(UUID.randomUUID(), first);
        var moved =
                new CanonicalMeasurement(
                        first.equipmentId(),
                        "UPDATED_TYPE",
                        first.plantCode(),
                        "OTHER-LINE",
                        "OTHER-STATION",
                        first.lotNumber(),
                        first.batchNumber(),
                        first.metricType(),
                        first.numericValue(),
                        first.standardUnit(),
                        NOW.plusSeconds(60),
                        first.qualityStatus(),
                        first.source(),
                        "moved");
        warehouse.writeMeasurement(UUID.randomUUID(), moved);

        assertThat(equipmentCount()).isEqualTo(1);
        assertThat(
                        jdbc.queryForMap(
                                """
                SELECT equipment_type, line_code, station_code FROM warehouse.dim_equipment
                """))
                .isEqualTo(
                        Map.of(
                                "equipment_type",
                                "COATING_MACHINE",
                                "line_code",
                                "CELL-LINE-01",
                                "station_code",
                                "COATING-03"));
        assertThat(factCount()).isEqualTo(2);
    }

    @Test
    void dateKeysUseUtcEvenWhenJvmAndDatabaseSessionUseOtherTimezones() throws Exception {
        TimeZone originalTimezone = TimeZone.getDefault();
        try (var connection = jdbc.getDataSource().getConnection()) {
            String originalDatabaseTimezone;
            try (var statement = connection.createStatement();
                    var result = statement.executeQuery("SHOW TIME ZONE")) {
                result.next();
                originalDatabaseTimezone = result.getString(1);
            }
            var connectionJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            try {
                TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
                connectionJdbc.execute("SET TIME ZONE 'Pacific/Kiritimati'");
                var writer =
                        new JdbcWarehouseWriter(
                                connectionJdbc,
                                new DataSourceTransactionManager(connectionJdbc.getDataSource()));
                writer.writeMeasurement(
                        UUID.randomUUID(),
                        atPlantAndTime(
                                "utc-start",
                                "KH01",
                                Instant.parse("2026-09-10T00:30:00Z"),
                                QualityStatus.GOOD));
                writer.writeMeasurement(
                        UUID.randomUUID(),
                        atPlantAndTime(
                                "utc-end",
                                "KH01",
                                Instant.parse("2026-09-10T23:30:00Z"),
                                QualityStatus.GOOD));
                assertThat(
                                connectionJdbc.queryForObject(
                                        "SELECT date_key FROM warehouse.dim_date", Integer.class))
                        .isEqualTo(20260910);
                assertThat(
                                connectionJdbc.queryForObject(
                                        "SELECT full_date::text FROM warehouse.dim_date",
                                        String.class))
                        .isEqualTo("2026-09-10");
            } finally {
                connectionJdbc.queryForObject(
                        "SELECT set_config('TimeZone', ?, false)",
                        String.class,
                        originalDatabaseTimezone);
            }
        } finally {
            TimeZone.setDefault(originalTimezone);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentWritesReuseDimensionsAndDeduplicateTheSameMeasurement(boolean redelivery)
            throws Exception {
        UUID firstId = UUID.randomUUID();
        UUID secondId = redelivery ? firstId : UUID.randomUUID();
        var canonical = measurement("concurrent-warehouse", QualityStatus.WARNING);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first =
                    executor.submit(
                            () -> {
                                start.await();
                                warehouse.writeMeasurement(firstId, canonical);
                                return null;
                            });
            var second =
                    executor.submit(
                            () -> {
                                start.await();
                                warehouse.writeMeasurement(secondId, canonical);
                                return null;
                            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(factCount()).isEqualTo(redelivery ? 1 : 2);
        assertThat(equipmentCount()).isEqualTo(1);
        assertThat(dateCount()).isEqualTo(1);
    }

    @Test
    void analysisScriptCountsOnlyAbnormalMeasurementsPerUtcDayAndPlantEquipment() throws Exception {
        warehouse.writeMeasurement(
                UUID.randomUUID(), measurement("warning", QualityStatus.WARNING));
        warehouse.writeMeasurement(UUID.randomUUID(), measurement("bad", QualityStatus.BAD));
        warehouse.writeMeasurement(UUID.randomUUID(), measurement("good", QualityStatus.GOOD));
        warehouse.writeMeasurement(
                UUID.randomUUID(), atPlantAndTime("other-plant", "TN01", NOW, QualityStatus.BAD));
        warehouse.writeMeasurement(
                UUID.randomUUID(),
                atPlantAndTime(
                        "next-day",
                        "KH01",
                        Instant.parse("2026-09-11T00:00:00Z"),
                        QualityStatus.WARNING));

        // 執行交付給展示者的同一份 SQL，避免測試裡的副本與實際分析腳本逐漸不同。
        String analysis = Files.readString(Path.of("demo/warehouse-analysis.sql"));
        assertThat(jdbc.queryForList(analysis))
                .containsExactly(
                        abnormalCount("2026-09-10", "KH01", 2),
                        abnormalCount("2026-09-10", "TN01", 1),
                        abnormalCount("2026-09-11", "KH01", 1));
    }

    @Test
    void repeatedWarehouseWritesUseMeasurementIdAsIdempotencyKey() {
        UUID measurementId = UUID.randomUUID();
        var canonical = measurement("warehouse-idempotency", QualityStatus.GOOD);
        warehouse.writeMeasurement(measurementId, canonical);
        warehouse.writeMeasurement(measurementId, canonical);

        assertThat(factCount()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT numeric_value FROM warehouse.fact_measurement",
                                java.math.BigDecimal.class))
                .isEqualByComparingTo("35.2");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT source_record_id FROM warehouse.fact_measurement",
                                String.class))
                .isEqualTo("warehouse-idempotency");
    }

    @Test
    void warehouseCommitSurvivesCallerRollbackSoRedeliveryMustBeIdempotent() {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        transaction -> {
                            warehouse.writeMeasurement(
                                    UUID.randomUUID(),
                                    measurement("independent-warehouse", QualityStatus.GOOD));
                            transaction.setRollbackOnly();
                        });
        assertThat(factCount()).isEqualTo(1);
    }

    @Test
    void translatesWarehouseFailureIntoStableInfrastructureErrorAndRetainsCause() {
        jdbc.execute(
                "ALTER TABLE warehouse.fact_measurement ADD CONSTRAINT test_failure CHECK (numeric_value < 0)");
        try {
            assertThatThrownBy(
                            () ->
                                    warehouse.writeMeasurement(
                                            UUID.randomUUID(),
                                            measurement("warehouse-failure", QualityStatus.GOOD)))
                    .isInstanceOfSatisfying(
                            FactoryBridgeException.class,
                            exception -> {
                                assertThat(exception.errorCode())
                                        .isEqualTo(ErrorCode.DATA_WAREHOUSE_WRITE_FAILED);
                                assertThat(exception.getCause()).isNotNull();
                            });
            assertThat(factCount()).isZero();
            assertThat(equipmentCount()).isZero();
            assertThat(dateCount()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE warehouse.fact_measurement DROP CONSTRAINT test_failure");
        }
    }

    private long factCount() {
        return jdbc.queryForObject("SELECT count(*) FROM warehouse.fact_measurement", Long.class);
    }

    private long equipmentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM warehouse.dim_equipment", Long.class);
    }

    private long dateCount() {
        return jdbc.queryForObject("SELECT count(*) FROM warehouse.dim_date", Long.class);
    }

    private CanonicalMeasurement atPlantAndTime(
            String sourceRecordId, String plant, Instant measuredAt, QualityStatus quality) {
        var base = measurement(sourceRecordId, quality);
        return new CanonicalMeasurement(
                base.equipmentId(),
                base.equipmentType(),
                plant,
                base.lineCode(),
                base.stationCode(),
                base.lotNumber(),
                base.batchNumber(),
                base.metricType(),
                base.numericValue(),
                base.standardUnit(),
                measuredAt,
                quality,
                base.source(),
                sourceRecordId);
    }

    private Map<String, Object> abnormalCount(String date, String plant, long count) {
        return Map.of(
                "date",
                Date.valueOf(date),
                "equipment_id",
                "EQ-CT-003",
                "equipment_type",
                "COATING_MACHINE",
                "plant_code",
                plant,
                "abnormal_count",
                count);
    }
}
