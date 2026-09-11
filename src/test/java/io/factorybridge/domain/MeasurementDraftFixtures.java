package io.factorybridge.domain;

import java.util.HashMap;
import java.util.Map;

/** 每個測試只宣告相對於合法來源資料的差異，讓失敗的業務規則一眼可見。 */
final class MeasurementDraftFixtures {
    private MeasurementDraftFixtures() {}

    static MeasurementDraft validDraft() {
        return draftWith(Map.of());
    }

    static MeasurementDraft draftWith(String field, String value) {
        Map<String, String> changes = new HashMap<>();
        changes.put(field, value);
        return draftWith(changes);
    }

    static MeasurementDraft draftWith(Map<String, String> changes) {
        Map<String, String> fields = new HashMap<>();
        fields.put("sourceSystem", "MES_A");
        fields.put("sourceRecordId", "MES-A-20260910-000001");
        fields.put("plantCode", "KH01");
        fields.put("productionLine", "CELL-LINE-01");
        fields.put("stationCode", "COATING-03");
        fields.put("equipmentId", "EQ-CT-003");
        fields.put("equipmentType", "COATING_MACHINE");
        fields.put("lotNumber", "LOT-20260910-0012");
        fields.put("batchNumber", "BATCH-00192");
        fields.put("metricCode", "TEMP");
        fields.put("metricName", "Chamber Temperature");
        fields.put("value", "95.36");
        fields.put("unit", "F");
        fields.put("eventTime", "2026/09/10 14:30:22");
        fields.put("qualityStatus", "OK");
        fields.put("operatorId", "OP1024");
        fields.put("receivedAt", null);
        if (!fields.keySet().containsAll(changes.keySet())) {
            throw new IllegalArgumentException("Unknown fixture field: " + changes.keySet());
        }
        fields.putAll(changes);
        return new MeasurementDraft(
                fields.get("sourceSystem"),
                fields.get("sourceRecordId"),
                fields.get("plantCode"),
                fields.get("productionLine"),
                fields.get("stationCode"),
                fields.get("equipmentId"),
                fields.get("equipmentType"),
                fields.get("lotNumber"),
                fields.get("batchNumber"),
                fields.get("metricCode"),
                fields.get("metricName"),
                fields.get("value"),
                fields.get("unit"),
                fields.get("eventTime"),
                fields.get("qualityStatus"),
                fields.get("operatorId"),
                fields.get("receivedAt"));
    }
}
