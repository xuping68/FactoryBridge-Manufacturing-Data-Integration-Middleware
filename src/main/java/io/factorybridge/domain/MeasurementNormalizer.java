package io.factorybridge.domain;

import java.time.Instant;

/** 無狀態、無副作用的正規化入口；驗證失敗不會寫入資料庫或呼叫下游。 各規則都是純 Java，不需要 mock Spring context 即可驗證製造量測語意。 */
public final class MeasurementNormalizer {
    private final MeasurementDraftSanitizer sanitizer = new MeasurementDraftSanitizer();
    private final MeasurementValidator validator = new MeasurementValidator();
    private final MetricValueNormalizer metricNormalizer = new MetricValueNormalizer();
    private final MeasurementTimeParser timeParser = new MeasurementTimeParser();

    public CanonicalMeasurement normalizeMeasurement(MeasurementDraft draft) {
        MeasurementDraft cleaned = sanitizer.sanitizeDraft(draft);
        validator.validateTextFields(cleaned);
        MetricValueNormalizer.NormalizedMetric metric =
                metricNormalizer.normalizeMetricValue(
                        cleaned.metricCode(), cleaned.value(), cleaned.unit());
        Instant measuredAt =
                timeParser.parseMeasuredAt(cleaned.sourceSystem(), cleaned.eventTime());
        QualityStatus qualityStatus = normalizeQualityStatus(cleaned.qualityStatus());

        return new CanonicalMeasurement(
                cleaned.equipmentId(),
                cleaned.equipmentType(),
                cleaned.plantCode(),
                cleaned.productionLine(),
                cleaned.stationCode(),
                cleaned.lotNumber(),
                cleaned.batchNumber(),
                metric.metricType(),
                metric.value(),
                metric.standardUnit(),
                measuredAt,
                qualityStatus,
                cleaned.sourceSystem(),
                cleaned.sourceRecordId());
    }

    private QualityStatus normalizeQualityStatus(String status) {
        return switch (status) {
            case "OK", "PASS", "GOOD" -> QualityStatus.GOOD;
            case "WARN", "WARNING" -> QualityStatus.WARNING;
            case "NG", "FAIL", "BAD" -> QualityStatus.BAD;
            default ->
                    throw new FactoryBridgeException(
                            ErrorCode.INVALID_QUALITY_STATUS,
                            ErrorCode.INVALID_QUALITY_STATUS.defaultMessage());
        };
    }
}
