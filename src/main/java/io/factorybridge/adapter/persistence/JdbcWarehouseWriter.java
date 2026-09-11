package io.factorybridge.adapter.persistence;

import io.factorybridge.application.port.WarehouseWriter;
import io.factorybridge.domain.CanonicalMeasurement;
import io.factorybridge.domain.ErrorCode;
import io.factorybridge.domain.FactoryBridgeException;
import java.sql.Timestamp;
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
    }

    @Override
    public void writeMeasurement(UUID measurementId, CanonicalMeasurement measurement) {
        try {
            // TransactionTemplate 的 catch 範圍包含 commit failure，避免 proxy 提交錯誤漏掉映射。
            warehouseTransaction.executeWithoutResult(
                    status -> insertFactIfAbsent(measurementId, measurement));
        } catch (DataAccessException | TransactionException exception) {
            throw new FactoryBridgeException(
                    ErrorCode.DATA_WAREHOUSE_WRITE_FAILED,
                    "Data warehouse could not persist the measurement.",
                    exception);
        }
    }

    private void insertFactIfAbsent(UUID measurementId, CanonicalMeasurement measurement) {
        jdbc.update(
                """
                INSERT INTO warehouse.fact_measurement
                  (measurement_id, equipment_id, equipment_type, plant_code, line_code, station_code,
                   lot_number, batch_number, metric_type, numeric_value, standard_unit, measured_at,
                   quality_status, source, source_record_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (measurement_id) DO NOTHING
                """,
                measurementId,
                measurement.equipmentId(),
                measurement.equipmentType(),
                measurement.plantCode(),
                measurement.lineCode(),
                measurement.stationCode(),
                measurement.lotNumber(),
                measurement.batchNumber(),
                measurement.metricType().name(),
                measurement.numericValue(),
                measurement.standardUnit(),
                Timestamp.from(measurement.measuredAt()),
                measurement.qualityStatus().name(),
                measurement.source(),
                measurement.sourceRecordId());
    }
}
