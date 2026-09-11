package io.factorybridge.application;

import io.factorybridge.application.model.IngestionResult;
import io.factorybridge.application.model.SourceRecordIdentity;
import io.factorybridge.application.port.MeasurementPayloadDecoder;
import io.factorybridge.application.port.MeasurementStore;
import io.factorybridge.application.port.StagingStore;
import io.factorybridge.domain.ErrorCode;
import io.factorybridge.domain.FactoryBridgeException;
import io.factorybridge.domain.MeasurementDraft;
import io.factorybridge.domain.MeasurementNormalizer;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class MeasurementIngestionService {
    private final StagingStore stagingStore;
    private final MeasurementPayloadDecoder payloadDecoder;
    private final MeasurementNormalizer normalizer;
    private final MeasurementStore measurementStore;
    private final Clock clock;

    public MeasurementIngestionService(
            StagingStore stagingStore,
            MeasurementPayloadDecoder payloadDecoder,
            MeasurementNormalizer normalizer,
            MeasurementStore measurementStore,
            Clock clock) {
        this.stagingStore = stagingStore;
        this.payloadDecoder = payloadDecoder;
        this.normalizer = normalizer;
        this.measurementStore = measurementStore;
        this.clock = clock;
    }

    public IngestionResult ingestMeasurement(String rawPayload, String correlationId) {
        return validateAndPersistMeasurement(rawPayload, correlationId, Optional.empty());
    }

    public IngestionResult ingestFetchedMeasurement(
            String rawPayload, String correlationId, String source, String recordId) {
        return validateAndPersistMeasurement(
                rawPayload, correlationId, Optional.of(new SourceRecordIdentity(source, recordId)));
    }

    private IngestionResult validateAndPersistMeasurement(
            String rawPayload, String correlationId, Optional<SourceRecordIdentity> expected) {
        // 原始資料與匯入意圖必須一併留存；後續 crash 或 audit 失敗也不會讓 replay 遺失來源約束。
        UUID stagingId =
                expected.isPresent()
                        ? stagingStore.recordReceived(
                                rawPayload, correlationId, clock.instant(), expected.get())
                        : stagingStore.recordReceived(rawPayload, correlationId, clock.instant());
        try {
            var draft = payloadDecoder.decodeMeasurement(rawPayload);
            // 先核對來源識別，避免無效單位等其他錯誤遮蔽來源回錯資料的事實。
            expected.ifPresent(identity -> verifyMeasurementIdentity(draft, identity));
            var canonical = normalizer.normalizeMeasurement(draft);
            var acceptance =
                    measurementStore.acceptMeasurement(
                            stagingId,
                            canonical,
                            payloadDecoder.fingerprintPayload(rawPayload),
                            correlationId,
                            clock.instant());
            return new IngestionResult(
                    stagingId, acceptance.measurementId(), acceptance.duplicate());
        } catch (FactoryBridgeException failure) {
            throw rejectStagedMeasurement(stagingId, failure);
        } catch (RuntimeException unexpectedFailure) {
            // 最後一道應用邊界保留 stagingId 與原始 cause，不能讓程式錯誤失去可追查的收件識別。
            throw rejectStagedMeasurement(
                    stagingId,
                    new FactoryBridgeException(
                            ErrorCode.INTERNAL_ERROR,
                            ErrorCode.INTERNAL_ERROR.defaultMessage(),
                            unexpectedFailure));
        }
    }

    private IngestionRejectedException rejectStagedMeasurement(
            UUID stagingId, FactoryBridgeException failure) {
        // rejection 獨立提交；不把驗證失敗的原始資料與 canonical 交易一起 rollback。
        try {
            stagingStore.recordRejection(stagingId, failure.errorCode(), failure.getMessage());
        } catch (RuntimeException auditFailure) {
            // 保留原始錯誤；staging 仍為 RECEIVED，可透過識別查詢並重跑。
            failure.addSuppressed(auditFailure);
        }
        return new IngestionRejectedException(stagingId, failure);
    }

    private void verifyMeasurementIdentity(MeasurementDraft draft, SourceRecordIdentity expected) {
        String actualSource =
                draft == null || draft.sourceSystem() == null
                        ? null
                        : draft.sourceSystem().strip().toUpperCase(Locale.ROOT);
        String actualRecord =
                draft == null || draft.sourceRecordId() == null
                        ? null
                        : draft.sourceRecordId().strip();
        if (!expected.sourceSystem().equals(actualSource)
                || !expected.sourceRecordId().equals(actualRecord)) {
            throw new FactoryBridgeException(
                    ErrorCode.EXTERNAL_RECORD_MISMATCH,
                    "External response identity does not match the requested record.");
        }
    }
}
