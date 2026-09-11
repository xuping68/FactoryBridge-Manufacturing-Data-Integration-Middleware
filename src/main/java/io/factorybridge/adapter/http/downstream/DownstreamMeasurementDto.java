package io.factorybridge.adapter.http.downstream;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 下游 v1 契約：訊息識別與量測識別分開，重送同一 delivery 時 messageId 不變。 */
public record DownstreamMeasurementDto(
        int schemaVersion,
        UUID messageId,
        UUID measurementId,
        Asset asset,
        Measurement measurement,
        Traceability traceability) {

    public record Asset(
            String equipmentId,
            String equipmentType,
            String plantCode,
            String lineCode,
            String stationCode) {}

    public record Measurement(
            String metricType,
            BigDecimal value,
            String unit,
            Instant measuredAt,
            String qualityStatus) {}

    public record Traceability(
            String sourceSystem, String sourceRecordId, String lotNumber, String batchNumber) {}
}
