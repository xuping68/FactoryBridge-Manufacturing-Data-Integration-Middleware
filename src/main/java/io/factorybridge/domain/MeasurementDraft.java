package io.factorybridge.domain;

/**
 * 邊界 adapter 提交的來源資料；保留字串以便由領域規則辨識錯誤，避免反序列化先丟失語意。 此物件不承擔任何 HTTP、JSON 或持久化格式契約。
 * equipmentType、lotNumber、batchNumber、metricName、operatorId、receivedAt 可省略，其餘必填。 receivedAt 是來源提供的
 * metadata；系統的實際收件時間由應用層另外記錄。
 */
public record MeasurementDraft(
        String sourceSystem,
        String sourceRecordId,
        String plantCode,
        String productionLine,
        String stationCode,
        String equipmentId,
        String equipmentType,
        String lotNumber,
        String batchNumber,
        String metricCode,
        String metricName,
        String value,
        String unit,
        String eventTime,
        String qualityStatus,
        String operatorId,
        String receivedAt) {}
