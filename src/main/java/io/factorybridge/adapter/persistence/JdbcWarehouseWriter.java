package io.factorybridge.adapter.persistence;

import io.factorybridge.application.port.WarehouseWriter;
import io.factorybridge.domain.CanonicalMeasurement;
import io.factorybridge.domain.ErrorCode;
import io.factorybridge.domain.FactoryBridgeException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** 倉儲以 measurementId 去重，支援 worker 在寫入成功、回報之前中斷後重送。 */
@Repository
public class JdbcWarehouseWriter implements WarehouseWriter {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate warehouseTransaction;

    public JdbcWarehouseWriter(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.warehouseTransaction = new TransactionTemplate(transactionManager);
        warehouseTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // 競爭中的 INSERT 等待另一筆提交後，下一個 SELECT 必須看見已存在的維度。
        warehouseTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public void writeMeasurement(UUID measurementId, CanonicalMeasurement measurement) {
        try {
            // TransactionTemplate 的 catch 範圍包含 commit failure，避免 proxy 提交錯誤漏掉映射。
            warehouseTransaction.executeWithoutResult(
                    status -> {
                        long equipmentKey = findOrCreateEquipmentKey(measurement);
                        int dateKey = findOrCreateDateKey(measurement);
                        insertFactIfAbsent(measurementId, measurement, equipmentKey, dateKey);
                    });
        } catch (DataAccessException | TransactionException exception) {
            throw new FactoryBridgeException(
                    ErrorCode.DATA_WAREHOUSE_WRITE_FAILED,
                    "Data warehouse could not persist the measurement.",
                    exception);
        }
    }

    private long findOrCreateEquipmentKey(CanonicalMeasurement measurement) {
        // 廠區內的設備代碼才是 business key；首次載入的屬性固定，重送不改寫維度。
        jdbc.update(
                """
                INSERT INTO warehouse.dim_equipment
                  (equipment_id, equipment_type, plant_code, line_code, station_code)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (plant_code, equipment_id) DO NOTHING
                """,
                measurement.equipmentId(),
                measurement.equipmentType(),
                measurement.plantCode(),
                measurement.lineCode(),
                measurement.stationCode());
        return jdbc.queryForObject(
                """
                SELECT equipment_key FROM warehouse.dim_equipment
                WHERE plant_code = ? AND equipment_id = ?
                """,
                Long.class,
                measurement.plantCode(),
                measurement.equipmentId());
    }

    private int findOrCreateDateKey(CanonicalMeasurement measurement) {
        // 分析日期是倉儲規則；固定 UTC，不能由 JVM 或 DB session 的預設時區決定。
        LocalDate date = measurement.measuredAt().atOffset(ZoneOffset.UTC).toLocalDate();
        int dateKey = Integer.parseInt(date.format(DateTimeFormatter.BASIC_ISO_DATE));
        jdbc.update(
                """
                INSERT INTO warehouse.dim_date (date_key, full_date, year, quarter, month, day)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (date_key) DO NOTHING
                """,
                dateKey,
                date,
                date.getYear(),
                date.get(IsoFields.QUARTER_OF_YEAR),
                date.getMonthValue(),
                date.getDayOfMonth());
        return dateKey;
    }

    private void insertFactIfAbsent(
            UUID measurementId, CanonicalMeasurement measurement, long equipmentKey, int dateKey) {
        jdbc.update(
                """
                INSERT INTO warehouse.fact_measurement
                  (measurement_id, equipment_key, date_key,
                   metric_type, numeric_value, standard_unit, measured_at,
                   quality_status, source, source_record_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (measurement_id) DO NOTHING
                """,
                measurementId,
                equipmentKey,
                dateKey,
                measurement.metricType().name(),
                measurement.numericValue(),
                measurement.standardUnit(),
                Timestamp.from(measurement.measuredAt()),
                measurement.qualityStatus().name(),
                measurement.source(),
                measurement.sourceRecordId());
    }
}
