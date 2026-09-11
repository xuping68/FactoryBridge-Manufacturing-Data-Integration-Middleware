package io.factorybridge.domain;

import java.util.Locale;

/** 清洗只做可解釋的格式整理，不猜測小數點、時區或來源識別碼的意義。 */
final class MeasurementDraftSanitizer {
    MeasurementDraft sanitizeDraft(MeasurementDraft draft) {
        if (draft == null) {
            throw new FactoryBridgeException(
                    ErrorCode.INVALID_PAYLOAD, "Measurement payload is required.");
        }
        return new MeasurementDraft(
                normalizeCode(draft.sourceSystem()),
                stripText(draft.sourceRecordId()),
                normalizeCode(draft.plantCode()),
                normalizeCode(draft.productionLine()),
                normalizeCode(draft.stationCode()),
                normalizeCode(draft.equipmentId()),
                normalizeCode(draft.equipmentType()),
                stripText(draft.lotNumber()),
                stripText(draft.batchNumber()),
                normalizeCode(draft.metricCode()),
                stripText(draft.metricName()),
                stripText(draft.value()),
                normalizeCode(draft.unit()),
                stripText(draft.eventTime()),
                normalizeCode(draft.qualityStatus()),
                stripText(draft.operatorId()),
                stripText(draft.receivedAt()));
    }

    private String normalizeCode(String value) {
        String stripped = stripText(value);
        // 設備代碼不應因主機使用土耳其語等預設 Locale 而產生不同的正規化結果。
        return stripped == null ? null : stripped.toUpperCase(Locale.ROOT);
    }

    private String stripText(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
