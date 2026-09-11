package io.factorybridge.application;

import static io.factorybridge.application.ApplicationTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import io.factorybridge.application.model.IngestionResult;
import io.factorybridge.application.model.StagingRecord;
import io.factorybridge.application.port.MeasurementPayloadDecoder;
import io.factorybridge.application.port.MeasurementStore;
import io.factorybridge.application.port.StagingStore;
import io.factorybridge.domain.ErrorCode;
import io.factorybridge.domain.FactoryBridgeException;
import io.factorybridge.domain.MeasurementNormalizer;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StagingReplayServiceTest {
    @Mock private StagingStore stagingStore;
    @Mock private MeasurementIngestionService ingestionService;
    private StagingReplayService replayService;

    @BeforeEach
    void createReplayService() {
        replayService = new StagingReplayService(stagingStore, ingestionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECEIVED", "REJECTED", "ACCEPTED", "DUPLICATE"})
    void replaysOriginalEvidenceAsANewAttemptWithoutRewritingHistory(String originalStatus) {
        StagingRecord original =
                new StagingRecord(
                        STAGING_ID,
                        RAW_PAYLOAD,
                        "original-correlation",
                        NOW,
                        originalStatus,
                        null,
                        null,
                        null,
                        null);
        IngestionResult expected = new IngestionResult(REPLAY_STAGING_ID, MEASUREMENT_ID, true);
        when(stagingStore.findStagingRecord(STAGING_ID)).thenReturn(Optional.of(original));
        when(ingestionService.ingestMeasurement(RAW_PAYLOAD, CORRELATION_ID)).thenReturn(expected);

        IngestionResult replay = replayService.replayStagingRecord(STAGING_ID, CORRELATION_ID);

        assertEquals(expected, replay);
        assertEquals(originalStatus, original.status());
        verify(stagingStore).findStagingRecord(STAGING_ID);
        verifyNoMoreInteractions(stagingStore);
        verify(ingestionService).ingestMeasurement(RAW_PAYLOAD, CORRELATION_ID);
    }

    @Test
    void returnsAStableBusinessErrorWhenTheOriginalRecordDoesNotExist() {
        when(stagingStore.findStagingRecord(STAGING_ID)).thenReturn(Optional.empty());

        FactoryBridgeException failure =
                assertThrows(
                        FactoryBridgeException.class,
                        () -> replayService.replayStagingRecord(STAGING_ID, CORRELATION_ID));

        assertEquals(ErrorCode.RECORD_NOT_FOUND, failure.errorCode());
        verifyNoInteractions(ingestionService);
    }

    @Test
    void preventsReplayFromBypassingTheExpectedIdentityOfAFailedImport() {
        StagingRecord wrongExternalRecord =
                new StagingRecord(
                        STAGING_ID,
                        RAW_PAYLOAD,
                        CORRELATION_ID,
                        NOW,
                        "REJECTED",
                        null,
                        ErrorCode.EXTERNAL_RECORD_MISMATCH.name(),
                        "External response identity does not match the requested record.",
                        EXPECTED_SOURCE);
        when(stagingStore.findStagingRecord(STAGING_ID))
                .thenReturn(Optional.of(wrongExternalRecord));

        FactoryBridgeException failure =
                assertThrows(
                        FactoryBridgeException.class,
                        () -> replayService.replayStagingRecord(STAGING_ID, CORRELATION_ID));

        assertEquals(ErrorCode.STAGING_NOT_REPLAYABLE, failure.errorCode());
        verifyNoInteractions(ingestionService);
        verify(stagingStore).findStagingRecord(STAGING_ID);
        verifyNoMoreInteractions(stagingStore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECEIVED", "REJECTED", "ACCEPTED", "DUPLICATE"})
    void restoresTheOriginalImportIntentWhenReplayingAnyRecoverableStagingState(String status) {
        StagingRecord fetched =
                new StagingRecord(
                        STAGING_ID,
                        RAW_PAYLOAD,
                        "original-correlation",
                        NOW,
                        status,
                        null,
                        null,
                        null,
                        EXPECTED_SOURCE);
        IngestionResult expected = new IngestionResult(REPLAY_STAGING_ID, MEASUREMENT_ID, false);
        when(stagingStore.findStagingRecord(STAGING_ID)).thenReturn(Optional.of(fetched));
        when(ingestionService.ingestFetchedMeasurement(
                        RAW_PAYLOAD, CORRELATION_ID, "MES_A", SOURCE_RECORD_ID))
                .thenReturn(expected);

        IngestionResult replay = replayService.replayStagingRecord(STAGING_ID, CORRELATION_ID);

        assertEquals(expected, replay);
        verify(ingestionService)
                .ingestFetchedMeasurement(RAW_PAYLOAD, CORRELATION_ID, "MES_A", SOURCE_RECORD_ID);
        verifyNoMoreInteractions(ingestionService);
    }

    @Test
    void stillRejectsWrongSourceAfterAnInterruptedImportLeftNoRejectionErrorCode() {
        // 模擬初次 raw+intent 已提交，但 process crash 或 audit DB 失敗後仍保留 RECEIVED。
        StagingRecord interrupted =
                new StagingRecord(
                        STAGING_ID,
                        RAW_PAYLOAD,
                        "original-correlation",
                        NOW,
                        "RECEIVED",
                        null,
                        null,
                        null,
                        EXPECTED_SOURCE);
        MeasurementPayloadDecoder decoder = mock(MeasurementPayloadDecoder.class);
        MeasurementStore measurements = mock(MeasurementStore.class);
        MeasurementIngestionService recoveredIngestion =
                new MeasurementIngestionService(
                        stagingStore, decoder, new MeasurementNormalizer(), measurements, CLOCK);
        StagingReplayService recoveredReplay =
                new StagingReplayService(stagingStore, recoveredIngestion);
        when(stagingStore.findStagingRecord(STAGING_ID)).thenReturn(Optional.of(interrupted));
        when(stagingStore.recordReceived(RAW_PAYLOAD, CORRELATION_ID, NOW, EXPECTED_SOURCE))
                .thenReturn(REPLAY_STAGING_ID);
        when(decoder.decodeMeasurement(RAW_PAYLOAD))
                .thenReturn(draft("MES_B", "wrong-record", "95.36"));

        IngestionRejectedException rejection =
                assertThrows(
                        IngestionRejectedException.class,
                        () -> recoveredReplay.replayStagingRecord(STAGING_ID, CORRELATION_ID));

        assertEquals(ErrorCode.EXTERNAL_RECORD_MISMATCH, rejection.failure().errorCode());
        assertEquals(REPLAY_STAGING_ID, rejection.stagingId());
        verify(stagingStore).recordReceived(RAW_PAYLOAD, CORRELATION_ID, NOW, EXPECTED_SOURCE);
        verify(stagingStore, never()).recordReceived(RAW_PAYLOAD, CORRELATION_ID, NOW);
        verifyNoInteractions(measurements);
    }
}
