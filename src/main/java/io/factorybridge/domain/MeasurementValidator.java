package io.factorybridge.domain;

import java.util.List;
import java.util.Set;

/** 驗證已清洗的文字邊界；數值與時間的語意由各自的正規化規則驗證。 */
final class MeasurementValidator {
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final int MAX_SCALAR_LENGTH = 64;
    private static final Set<String> SUPPORTED_SOURCES = Set.of("MES_A", "MES_B");

    void validateTextFields(MeasurementDraft draft) {
        required("sourceSystem", draft.sourceSystem(), ErrorCode.INVALID_SOURCE_SYSTEM);
        required("sourceRecordId", draft.sourceRecordId(), ErrorCode.MISSING_REQUIRED_FIELD);
        required("plantCode", draft.plantCode(), ErrorCode.MISSING_REQUIRED_FIELD);
        required("productionLine", draft.productionLine(), ErrorCode.MISSING_REQUIRED_FIELD);
        required("stationCode", draft.stationCode(), ErrorCode.MISSING_REQUIRED_FIELD);
        required("equipmentId", draft.equipmentId(), ErrorCode.MISSING_EQUIPMENT_ID);
        required("metricCode", draft.metricCode(), ErrorCode.UNSUPPORTED_METRIC);
        required("value", draft.value(), ErrorCode.INVALID_MEASUREMENT_VALUE);
        required("unit", draft.unit(), ErrorCode.UNSUPPORTED_UNIT);
        required("eventTime", draft.eventTime(), ErrorCode.INVALID_EVENT_TIME);
        required("qualityStatus", draft.qualityStatus(), ErrorCode.INVALID_QUALITY_STATUS);

        validateTextBoundaries(draft);
        if (!SUPPORTED_SOURCES.contains(draft.sourceSystem())) {
            throw new FactoryBridgeException(
                    ErrorCode.INVALID_SOURCE_SYSTEM,
                    ErrorCode.INVALID_SOURCE_SYSTEM.defaultMessage());
        }
    }

    private void validateTextBoundaries(MeasurementDraft draft) {
        // 非必要欄位仍有上限，避免經過驗證的資料最後才被資料庫拒絕。
        List<TextField> identifiers =
                List.of(
                        new TextField("sourceSystem", draft.sourceSystem()),
                        new TextField("sourceRecordId", draft.sourceRecordId()),
                        new TextField("plantCode", draft.plantCode()),
                        new TextField("productionLine", draft.productionLine()),
                        new TextField("stationCode", draft.stationCode()),
                        new TextField("equipmentId", draft.equipmentId()),
                        new TextField("equipmentType", draft.equipmentType()),
                        new TextField("lotNumber", draft.lotNumber()),
                        new TextField("batchNumber", draft.batchNumber()),
                        new TextField("metricCode", draft.metricCode()),
                        new TextField("metricName", draft.metricName()),
                        new TextField("unit", draft.unit()),
                        new TextField("qualityStatus", draft.qualityStatus()),
                        new TextField("operatorId", draft.operatorId()));
        identifiers.forEach(field -> validateTextBoundary(field, MAX_IDENTIFIER_LENGTH));
        validateTextBoundary(new TextField("value", draft.value()), MAX_SCALAR_LENGTH);
        validateTextBoundary(new TextField("eventTime", draft.eventTime()), MAX_SCALAR_LENGTH);
        validateTextBoundary(new TextField("receivedAt", draft.receivedAt()), MAX_SCALAR_LENGTH);
    }

    private void required(String fieldName, String value, ErrorCode errorCode) {
        if (value == null) {
            throw new FactoryBridgeException(errorCode, fieldName + " is required.");
        }
    }

    private void validateTextBoundary(TextField field, int maximumLength) {
        if (field.value() == null) {
            return;
        }
        if (field.value().length() > maximumLength) {
            throw new FactoryBridgeException(
                    ErrorCode.FIELD_TOO_LONG,
                    field.name() + " must not exceed " + maximumLength + " characters.");
        }
        // PostgreSQL 不接受 NUL；未配對 surrogate 也不能安全編碼成 UTF-8。
        if (field.value()
                .codePoints()
                .anyMatch(
                        codePoint ->
                                Character.isISOControl(codePoint)
                                        || (codePoint >= Character.MIN_SURROGATE
                                                && codePoint <= Character.MAX_SURROGATE))) {
            throw new FactoryBridgeException(
                    ErrorCode.INVALID_PAYLOAD,
                    field.name() + " must not contain control characters or malformed Unicode.");
        }
    }

    private record TextField(String name, String value) {}
}
