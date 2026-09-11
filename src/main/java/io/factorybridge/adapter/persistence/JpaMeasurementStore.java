package io.factorybridge.adapter.persistence;

import io.factorybridge.application.model.Acceptance;
import io.factorybridge.application.model.StoredMeasurement;
import io.factorybridge.application.port.MeasurementStore;
import io.factorybridge.domain.CanonicalMeasurement;
import io.factorybridge.domain.ErrorCode;
import io.factorybridge.domain.FactoryBridgeException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 此交易邊界只承擔「接受資料」，網路呼叫與倉儲寫入均由 outbox worker 執行。 */
@Repository
public class JpaMeasurementStore implements MeasurementStore {
    private final MeasurementJpaRepository measurements;
    private final StagingJpaRepository stagingRecords;
    private final JdbcTemplate jdbc;
    private final OutboxEnqueuer outbox;
    private final PersistenceTransactions transactions;

    public JpaMeasurementStore(
            MeasurementJpaRepository measurements,
            StagingJpaRepository stagingRecords,
            JdbcTemplate jdbc,
            OutboxEnqueuer outbox,
            PersistenceTransactions transactions) {
        this.measurements = measurements;
        this.stagingRecords = stagingRecords;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    @Override
    public Acceptance acceptMeasurement(
            UUID stagingId,
            CanonicalMeasurement measurement,
            String payloadHash,
            String correlationId,
            Instant acceptedAt) {
        return transactions.write(
                () ->
                        acceptInTransaction(
                                stagingId, measurement, payloadHash, correlationId, acceptedAt));
    }

    private Acceptance acceptInTransaction(
            UUID stagingId,
            CanonicalMeasurement measurement,
            String payloadHash,
            String correlationId,
            Instant acceptedAt) {
        StagingEntity staging =
                stagingRecords
                        .findForAcceptance(stagingId)
                        .orElseThrow(
                                () ->
                                        new FactoryBridgeException(
                                                ErrorCode.RECORD_NOT_FOUND,
                                                "Staging record was not found."));
        UUID proposedId = UUID.randomUUID();
        boolean inserted = insertIfAbsent(proposedId, measurement, payloadHash, acceptedAt);
        if (inserted) {
            outbox.enqueueDestinations(
                    proposedId, measurement.qualityStatus(), correlationId, acceptedAt);
            staging.recordAcceptance(proposedId, false);
            return new Acceptance(proposedId, false);
        }

        // ON CONFLICT 會等待競爭交易完成，接下來的 READ COMMITTED 查詢可見已提交的資料。
        // 不捕捉 unique violation：PostgreSQL 發生該錯誤後，整筆交易已無法繼續使用。
        MeasurementEntity existing =
                measurements
                        .findBySourceAndSourceRecordId(
                                measurement.source(), measurement.sourceRecordId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Conflicting measurement could not be read."));
        if (!existing.payloadHash().equals(payloadHash)) {
            throw new FactoryBridgeException(
                    ErrorCode.DUPLICATE_SOURCE_RECORD,
                    "The source record identifier already exists with different measurement content.");
        }
        staging.recordAcceptance(existing.id(), true);
        return new Acceptance(existing.id(), true);
    }

    @Override
    public Optional<StoredMeasurement> findMeasurement(UUID measurementId) {
        return transactions.read(
                () ->
                        measurements
                                .findById(measurementId)
                                .map(MeasurementEntity::toStoredMeasurement));
    }

    @Override
    public List<StoredMeasurement> listMeasurements(int limit) {
        PageRequest page =
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return transactions.read(
                () ->
                        measurements.findAll(page).stream()
                                .map(MeasurementEntity::toStoredMeasurement)
                                .toList());
    }

    private boolean insertIfAbsent(
            UUID id, CanonicalMeasurement measurement, String hash, Instant createdAt) {
        return jdbc.update(
                        """
                INSERT INTO factorybridge.measurement
                  (id, equipment_id, equipment_type, plant_code, line_code, station_code, lot_number,
                   batch_number, metric_type, numeric_value, standard_unit, measured_at,
                   quality_status, source, source_record_id, payload_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source, source_record_id) DO NOTHING
                """,
                        id,
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
                        measurement.sourceRecordId(),
                        hash,
                        Timestamp.from(createdAt))
                == 1;
    }
}
