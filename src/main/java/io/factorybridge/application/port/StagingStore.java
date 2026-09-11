package io.factorybridge.application.port;

import io.factorybridge.application.model.SourceRecordIdentity;
import io.factorybridge.application.model.StagingRecord;
import io.factorybridge.domain.ErrorCode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface StagingStore {
    default UUID recordReceived(String rawPayload, String correlationId, Instant receivedAt) {
        return recordReceived(rawPayload, correlationId, receivedAt, null);
    }

    // null 表示 push；import 的請求識別必須與 raw 在同一獨立交易留存。
    UUID recordReceived(
            String rawPayload,
            String correlationId,
            Instant receivedAt,
            SourceRecordIdentity expectedSource);

    void recordRejection(UUID stagingId, ErrorCode errorCode, String message);

    Optional<StagingRecord> findStagingRecord(UUID stagingId);
}
