package io.factorybridge.adapter.web;

import io.factorybridge.application.model.StoredMeasurement;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MeasurementResponse(
        UUID id,
        String equipmentId,
        String equipmentType,
        String plantCode,
        String lineCode,
        String stationCode,
        String lotNumber,
        String batchNumber,
        String metricType,
        BigDecimal numericValue,
        String standardUnit,
        Instant measuredAt,
        String qualityStatus,
        String source,
        String sourceRecordId,
        Instant createdAt) {
    static MeasurementResponse fromStoredMeasurement(StoredMeasurement stored) {
        var value = stored.measurement();
        return new MeasurementResponse(
                stored.id(),
                value.equipmentId(),
                value.equipmentType(),
                value.plantCode(),
                value.lineCode(),
                value.stationCode(),
                value.lotNumber(),
                value.batchNumber(),
                value.metricType().name(),
                value.numericValue(),
                value.standardUnit(),
                value.measuredAt(),
                value.qualityStatus().name(),
                value.source(),
                value.sourceRecordId(),
                stored.createdAt());
    }
}
