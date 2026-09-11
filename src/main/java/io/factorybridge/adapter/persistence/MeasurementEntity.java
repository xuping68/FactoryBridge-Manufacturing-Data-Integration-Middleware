package io.factorybridge.adapter.persistence;

import io.factorybridge.application.model.StoredMeasurement;
import io.factorybridge.domain.CanonicalMeasurement;
import io.factorybridge.domain.MetricType;
import io.factorybridge.domain.QualityStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 持久層結構留在 adapter；domain 不需要 JPA annotation 或 lazy relation。 */
@Entity
@Table(name = "measurement", schema = "factorybridge")
class MeasurementEntity {
    @Id private UUID id;

    @Column(name = "equipment_id", nullable = false, length = 128)
    private String equipmentId;

    @Column(name = "equipment_type", length = 128)
    private String equipmentType;

    @Column(name = "plant_code", nullable = false, length = 128)
    private String plantCode;

    @Column(name = "line_code", nullable = false, length = 128)
    private String lineCode;

    @Column(name = "station_code", nullable = false, length = 128)
    private String stationCode;

    @Column(name = "lot_number", length = 128)
    private String lotNumber;

    @Column(name = "batch_number", length = 128)
    private String batchNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric_type", nullable = false, length = 32)
    private MetricType metricType;

    @Column(name = "numeric_value", nullable = false, precision = 20, scale = 6)
    private BigDecimal numericValue;

    @Column(name = "standard_unit", nullable = false, length = 16)
    private String standardUnit;

    @Column(name = "measured_at", nullable = false)
    private Instant measuredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality_status", nullable = false, length = 16)
    private QualityStatus qualityStatus;

    @Column(nullable = false, length = 128)
    private String source;

    @Column(name = "source_record_id", nullable = false, length = 128)
    private String sourceRecordId;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MeasurementEntity() {}

    UUID id() {
        return id;
    }

    String payloadHash() {
        return payloadHash;
    }

    StoredMeasurement toStoredMeasurement() {
        CanonicalMeasurement measurement =
                new CanonicalMeasurement(
                        equipmentId,
                        equipmentType,
                        plantCode,
                        lineCode,
                        stationCode,
                        lotNumber,
                        batchNumber,
                        metricType,
                        numericValue,
                        standardUnit,
                        measuredAt,
                        qualityStatus,
                        source,
                        sourceRecordId);
        return new StoredMeasurement(id, measurement, createdAt);
    }
}
