package io.factorybridge.adapter.http.downstream;

import io.factorybridge.domain.CanonicalMeasurement;
import java.util.UUID;

/** 下游 schema 改版僅調整此 adapter，不要求核心量測模型知道 JSON 結構。 */
public final class MeasurementTransformer {
    private static final int SCHEMA_VERSION = 1;

    public DownstreamMeasurementDto toDownstreamMeasurement(
            UUID deliveryId, UUID measurementId, CanonicalMeasurement canonical) {
        return new DownstreamMeasurementDto(
                SCHEMA_VERSION,
                deliveryId,
                measurementId,
                new DownstreamMeasurementDto.Asset(
                        canonical.equipmentId(),
                        canonical.equipmentType(),
                        canonical.plantCode(),
                        canonical.lineCode(),
                        canonical.stationCode()),
                new DownstreamMeasurementDto.Measurement(
                        canonical.metricType().name(),
                        canonical.numericValue(),
                        canonical.standardUnit(),
                        canonical.measuredAt(),
                        canonical.qualityStatus().name()),
                new DownstreamMeasurementDto.Traceability(
                        canonical.source(),
                        canonical.sourceRecordId(),
                        canonical.lotNumber(),
                        canonical.batchNumber()));
    }
}
