package io.factorybridge.adapter.http.external;

import io.swagger.v3.oas.annotations.media.Schema;

/** 外部契約可獨立演進；新增來源欄位不應污染 Canonical Model。 */
@Schema(
        description =
                """
                /api/v1 的來源量測契約；已知欄位必須是 JSON 字串或 null，不將 JSON number 自動轉成字串。
                sourceSystem 去除前後空白並轉大寫；sourceRecordId 去除前後空白、保留大小寫，兩者共同識別來源紀錄。
                相同來源識別只有完整 JSON 內容相同時才視為冪等重送；字串大小寫或未知欄位內容變動仍可能造成 409 衝突。
                JSON 物件欄位順序與排版空白不影響內容指紋。新增未知欄位會保留在 raw staging 並參與指紋，
                但不映射到 Canonical Model。此來源契約不要求 schemaVersion 欄位；下游另有獨立的版本化契約。
                """,
        requiredProperties = {
            "sourceSystem",
            "sourceRecordId",
            "plantCode",
            "productionLine",
            "stationCode",
            "equipmentId",
            "metricCode",
            "value",
            "unit",
            "eventTime",
            "qualityStatus"
        },
        example =
                """
                {
                  "sourceSystem": "MES_A",
                  "sourceRecordId": "MES-A-20260910-000001",
                  "plantCode": "KH01",
                  "productionLine": "CELL-LINE-01",
                  "stationCode": "COATING-03",
                  "equipmentId": "EQ-CT-003",
                  "equipmentType": "COATING_MACHINE",
                  "lotNumber": "LOT-20260910-0012",
                  "batchNumber": "BATCH-00192",
                  "metricCode": "TEMP",
                  "metricName": "Chamber Temperature",
                  "value": "95.36",
                  "unit": "F",
                  "eventTime": "2026/09/10 14:30:22",
                  "qualityStatus": "OK",
                  "operatorId": "OP1024"
                }
                """)
public record ExternalMeasurementDto(
        @Schema(description = "來源系統代碼；清洗後只接受 MES_A、MES_B。", example = "MES_A", maxLength = 128)
                String sourceSystem,
        @Schema(
                        description = "來源提供的穩定紀錄識別碼，保留大小寫；與來源系統共同構成冪等識別。",
                        example = "MES-A-20260910-000001",
                        maxLength = 128)
                String sourceRecordId,
        @Schema(description = "廠區代碼，清洗後統一大寫。", example = "KH01", maxLength = 128) String plantCode,
        @Schema(
                        description = "來源產線代碼，映射為 canonical lineCode。",
                        example = "CELL-LINE-01",
                        maxLength = 128)
                String productionLine,
        @Schema(description = "製程站點代碼。", example = "COATING-03", maxLength = 128)
                String stationCode,
        @Schema(description = "設備資產識別碼，清洗後統一大寫。", example = "EQ-CT-003", maxLength = 128)
                String equipmentId,
        @Schema(description = "選填的設備類型，清洗後統一大寫。", example = "COATING_MACHINE", maxLength = 128)
                String equipmentType,
        @Schema(
                        description = "選填的生產批號，保留大小寫供生產履歷追溯。",
                        example = "LOT-20260910-0012",
                        maxLength = 128)
                String lotNumber,
        @Schema(description = "選填的製程批次識別碼，保留大小寫。", example = "BATCH-00192", maxLength = 128)
                String batchNumber,
        @Schema(description = "來源量測代碼，依領域規則映射為標準 metricType。", example = "TEMP", maxLength = 128)
                String metricCode,
        @Schema(
                        description = "選填的來源顯示名稱；保留於 raw，不作為量測類型的判斷依據。",
                        example = "Chamber Temperature",
                        maxLength = 128)
                String metricName,
        @Schema(description = "以字串傳遞十進位量測值；不接受分隔千分位或非有限數值。", example = "95.36", maxLength = 64)
                String value,
        @Schema(
                        description = "來源單位，必須與 metricCode 相容；TEMP 的 F 會轉為標準 C。",
                        example = "F",
                        maxLength = 128)
                String unit,
        @Schema(
                        description =
                                "量測發生時間；接受有 offset 的 ISO 時間或 uuuu/MM/dd HH:mm:ss。"
                                        + "舊格式 MES_A 使用 Asia/Taipei，MES_B 使用 UTC；支援年份 2000 至 2100。",
                        example = "2026/09/10 14:30:22",
                        maxLength = 64)
                String eventTime,
        @Schema(
                        description =
                                "OK/PASS/GOOD → GOOD；WARN/WARNING → WARNING；NG/FAIL/BAD → BAD。",
                        example = "OK",
                        maxLength = 128)
                String qualityStatus,
        @Schema(description = "選填的來源操作員識別碼，僅保留於 raw staging。", example = "OP1024", maxLength = 128)
                String operatorId,
        @Schema(
                        description = "選填的來源接收時間字串，只保留於 raw；不取代 FactoryBridge 伺服器記錄的接收時間。",
                        example = "2026-09-10T06:30:25Z",
                        maxLength = 64)
                String receivedAt) {}
