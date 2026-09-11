package io.factorybridge.application;

import io.factorybridge.application.model.IngestionResult;
import io.factorybridge.application.port.StagingStore;
import io.factorybridge.domain.*;
import java.util.UUID;

public final class StagingReplayService {
    private final StagingStore stagingStore;
    private final MeasurementIngestionService ingestionService;

    public StagingReplayService(
            StagingStore stagingStore, MeasurementIngestionService ingestionService) {
        this.stagingStore = stagingStore;
        this.ingestionService = ingestionService;
    }

    public IngestionResult replayStagingRecord(UUID stagingId, String correlationId) {
        var previous =
                stagingStore
                        .findStagingRecord(stagingId)
                        .orElseThrow(
                                () ->
                                        new FactoryBridgeException(
                                                ErrorCode.RECORD_NOT_FOUND,
                                                "Staging record was not found."));
        // 來源回錯識別時只能重新取得資料；一般 replay 不能移除原匯入請求的 identity 保護。
        if (ErrorCode.EXTERNAL_RECORD_MISMATCH.name().equals(previous.errorCode())) {
            throw new FactoryBridgeException(
                    ErrorCode.STAGING_NOT_REPLAYABLE,
                    "An external identity mismatch must be resolved by importing the requested record again.");
        }
        // RECEIVED 也可能代表前次處理中斷；從收件時保存的意圖恢復，不能將 import 降級為 push。
        var expectedSource = previous.expectedSource();
        if (expectedSource != null) {
            return ingestionService.ingestFetchedMeasurement(
                    previous.rawPayload(),
                    correlationId,
                    expectedSource.sourceSystem(),
                    expectedSource.sourceRecordId());
        }
        // 每次 replay 新增 staging，保留前次結果；canonical 仍由 source key 去重。
        return ingestionService.ingestMeasurement(previous.rawPayload(), correlationId);
    }
}
