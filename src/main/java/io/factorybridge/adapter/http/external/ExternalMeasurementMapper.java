package io.factorybridge.adapter.http.external;

import io.factorybridge.domain.MeasurementDraft;

/** 顯式欄位對應讓來源改版的影響範圍可在 code review 看見。 */
final class ExternalMeasurementMapper {
    MeasurementDraft toMeasurementDraft(ExternalMeasurementDto external) {
        return new MeasurementDraft(
                external.sourceSystem(),
                external.sourceRecordId(),
                external.plantCode(),
                external.productionLine(),
                external.stationCode(),
                external.equipmentId(),
                external.equipmentType(),
                external.lotNumber(),
                external.batchNumber(),
                external.metricCode(),
                external.metricName(),
                external.value(),
                external.unit(),
                external.eventTime(),
                external.qualityStatus(),
                external.operatorId(),
                external.receivedAt());
    }
}
