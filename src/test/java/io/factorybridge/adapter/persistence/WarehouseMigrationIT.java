package io.factorybridge.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 從真實 V1 資料庫升級，避免只測空白 DB 而漏掉舊 volume 的資料保留問題。 */
@Testcontainers
class WarehouseMigrationIT {
    private static final UUID FIRST_LOADED_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("warehouse_upgrade")
                    .withUsername("factorybridge")
                    // 刻意讓 server timezone 不是 UTC，才能抓出日期回填依賴環境的錯誤。
                    .withCommand("postgres", "-c", "timezone=America/Los_Angeles");

    @Test
    void upgradesExistingV1FactsWithoutLosingMeasurementsOrLegacyAttributes() {
        try (var dataSource = new HikariDataSource()) {
            dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
            dataSource.setUsername(POSTGRES.getUsername());
            dataSource.setPassword(POSTGRES.getPassword());
            dataSource.setMaximumPoolSize(2);
            // PostgreSQL JDBC 會以 JVM timezone 覆寫 session，僅設定 server timezone 並不足夠。
            // 每條新連線都固定為非 UTC，讓 Flyway 與驗證查詢處於相同、可重現的環境。
            dataSource.setConnectionInitSql("SET TIME ZONE 'America/Los_Angeles'");
            verifyUpgradePreservesLegacyFacts(dataSource);
        }
    }

    private void verifyUpgradePreservesLegacyFacts(DataSource dataSource) {
        var jdbc = new JdbcTemplate(dataSource);
        Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema("public")
                .target("1")
                .load()
                .migrate();
        insertLegacyFacts(jdbc);

        List<Map<String, Object>> legacyRows =
                jdbc.queryForList(
                        "SELECT * FROM warehouse.fact_measurement ORDER BY measurement_id");
        List<Map<String, Object>> originalMeasures = readFactMeasures(jdbc);

        var upgrade = Flyway.configure().dataSource(dataSource).defaultSchema("public").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

        assertThat(readFactMeasures(jdbc)).isEqualTo(originalMeasures);
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM warehouse.fact_measurement_v1_archive ORDER BY measurement_id"))
                .isEqualTo(legacyRows);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM factorybridge.measurement", Long.class))
                .isZero();
        assertEquipmentBackfill(jdbc);
        assertUtcDateBackfill(jdbc);
        assertFactStructureAndForeignKeys(jdbc);

        // 再次啟動不得重做快照、產生新 surrogate key 或改寫 loaded_at。
        var equipmentBeforeRestart =
                jdbc.queryForList("SELECT * FROM warehouse.dim_equipment ORDER BY equipment_key");
        var factsBeforeRestart =
                jdbc.queryForList(
                        "SELECT * FROM warehouse.fact_measurement ORDER BY measurement_id");
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(upgrade.info().applied()).hasSize(2);
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM warehouse.dim_equipment ORDER BY equipment_key"))
                .isEqualTo(equipmentBeforeRestart);
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM warehouse.fact_measurement ORDER BY measurement_id"))
                .isEqualTo(factsBeforeRestart);
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM warehouse.fact_measurement_v1_archive ORDER BY measurement_id"))
                .isEqualTo(legacyRows);
    }

    private void insertLegacyFacts(JdbcTemplate jdbc) {
        // 同設備有晚入倉、同時入倉兩種屬性變更，並有另一廠區使用相同 equipment_id。
        // 刻意不建立 operational row：升級只能依賴倉儲自身已保存的資料。
        jdbc.update(
                """
                INSERT INTO warehouse.fact_measurement
                    (measurement_id, equipment_id, equipment_type, plant_code, line_code,
                     station_code, lot_number, batch_number, metric_type, numeric_value,
                     standard_unit, measured_at, quality_status, source, source_record_id, loaded_at)
                VALUES
                    ('00000000-0000-0000-0000-000000000002', 'EQ-SHARED', 'COATING_MACHINE',
                     'KH01', 'LINE-ORIGINAL', 'STATION-ORIGINAL', 'LOT-FIRST', 'BATCH-FIRST',
                     'TEMPERATURE', 35.200000, 'C', '2026-09-10T00:15:00Z', 'WARNING',
                     'MES_A', 'legacy-first', '2026-09-11T01:00:00Z'),
                    ('00000000-0000-0000-0000-000000000001', 'EQ-SHARED', 'CHANGED_MACHINE',
                     'KH01', 'LINE-LATER', 'STATION-LATER', 'LOT-LATER', 'BATCH-LATER',
                     'TEMPERATURE', 36.125000, 'C', '2026-09-10T23:45:00Z', 'BAD',
                     'MES_A', 'legacy-later', '2026-09-11T02:00:00Z'),
                    ('00000000-0000-0000-0000-000000000003', 'EQ-SHARED', 'TIED_MACHINE',
                     'KH01', 'LINE-TIED', 'STATION-TIED', 'LOT-TIED', 'BATCH-TIED',
                     'TEMPERATURE', 34.999999, 'C', '2026-09-10T00:30:00Z', 'GOOD',
                     'MES_A', 'legacy-tied', '2026-09-11T01:00:00Z'),
                    ('00000000-0000-0000-0000-000000000004', 'EQ-SHARED', 'OVEN',
                     'TP01', 'LINE-OTHER', 'STATION-OTHER', 'LOT-OTHER', 'BATCH-OTHER',
                     'TEMPERATURE', 98.765432, 'C', '2026-09-11T00:15:00Z', 'BAD',
                     'MES_B', 'legacy-other-plant', '2026-09-11T03:00:00Z')
                """);
    }

    private List<Map<String, Object>> readFactMeasures(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                """
                SELECT measurement_id, metric_type, numeric_value, standard_unit, measured_at,
                    quality_status, source, source_record_id, loaded_at
                FROM warehouse.fact_measurement
                ORDER BY measurement_id
                """);
    }

    private void assertEquipmentBackfill(JdbcTemplate jdbc) {
        assertThat(
                        jdbc.queryForList(
                                """
                                SELECT equipment_id, equipment_type, plant_code, line_code, station_code
                                FROM warehouse.dim_equipment ORDER BY plant_code
                                """))
                .containsExactly(
                        Map.of(
                                "equipment_id", "EQ-SHARED",
                                "equipment_type", "COATING_MACHINE",
                                "plant_code", "KH01",
                                "line_code", "LINE-ORIGINAL",
                                "station_code", "STATION-ORIGINAL"),
                        Map.of(
                                "equipment_id", "EQ-SHARED",
                                "equipment_type", "OVEN",
                                "plant_code", "TP01",
                                "line_code", "LINE-OTHER",
                                "station_code", "STATION-OTHER"));
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT count(*) FROM warehouse.fact_measurement fact
                                JOIN warehouse.dim_equipment equipment USING (equipment_key)
                                WHERE equipment.plant_code = 'KH01'
                                """,
                                Long.class))
                .isEqualTo(3);
    }

    private void assertUtcDateBackfill(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SHOW TIMEZONE", String.class))
                .isEqualTo("America/Los_Angeles");
        assertThat(jdbc.queryForList("SELECT * FROM warehouse.dim_date ORDER BY date_key"))
                .containsExactly(
                        Map.of(
                                "date_key", 20260910,
                                "full_date", Date.valueOf("2026-09-10"),
                                "year", 2026,
                                "quarter", 3,
                                "month", 9,
                                "day", 10),
                        Map.of(
                                "date_key", 20260911,
                                "full_date", Date.valueOf("2026-09-11"),
                                "year", 2026,
                                "quarter", 3,
                                "month", 9,
                                "day", 11));
        assertThat(
                        jdbc.queryForList(
                                "SELECT date_key FROM warehouse.fact_measurement ORDER BY source_record_id",
                                Integer.class))
                .containsExactly(20260910, 20260910, 20260911, 20260910);
    }

    private void assertFactStructureAndForeignKeys(JdbcTemplate jdbc) {
        assertThat(
                        jdbc.queryForList(
                                """
                                SELECT column_name FROM information_schema.columns
                                WHERE table_schema = 'warehouse' AND table_name = 'fact_measurement'
                                """,
                                String.class))
                .containsExactlyInAnyOrder(
                        "measurement_id",
                        "equipment_key",
                        "date_key",
                        "metric_type",
                        "numeric_value",
                        "standard_unit",
                        "quality_status",
                        "measured_at",
                        "source",
                        "source_record_id",
                        "loaded_at");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE warehouse.fact_measurement SET equipment_key = -1 WHERE measurement_id = ?",
                                        FIRST_LOADED_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE warehouse.fact_measurement SET date_key = 19990101 WHERE measurement_id = ?",
                                        FIRST_LOADED_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
