package io.factorybridge.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** 標準量測資料只保留業務語意；來源與下游格式的改版不應改變此模型。 */
public record CanonicalMeasurement(
        String equipmentId,
        String equipmentType,
        String plantCode,
        String lineCode,
        String stationCode,
        String lotNumber,
        String batchNumber,
        MetricType metricType,
        BigDecimal numericValue,
        String standardUnit,
        Instant measuredAt,
        QualityStatus qualityStatus,
        String source,
        String sourceRecordId) {}
