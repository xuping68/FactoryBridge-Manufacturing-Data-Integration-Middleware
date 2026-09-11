package io.factorybridge.application;

import static io.factorybridge.application.ApplicationTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.factorybridge.application.model.Acceptance;
import io.factorybridge.application.model.IngestionResult;
import io.factorybridge.application.port.MeasurementPayloadDecoder;
import io.factorybridge.application.port.MeasurementStore;
import io.factorybridge.application.port.StagingStore;
import io.factorybridge.domain.ErrorCode;
import io.factorybridge.domain.FactoryBridgeException;
import io.factorybridge.domain.MeasurementDraft;
import io.factorybridge.domain.MeasurementNormalizer;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MeasurementIngestionServiceTest {
    @Mock private StagingStore stagingStore;
    @Mock private MeasurementPayloadDecoder payloadDecoder;
    @Mock private MeasurementStore measurementStore;
    private MeasurementIngestionService ingestionService;

    @BeforeEach
    void createIngestionService() {
        ingestionService =
                new MeasurementIngestionService(
                        stagingStore,
                        payloadDecoder,
                        new MeasurementNormalizer(),
                        measurementStore,
                        CLOCK);
    }

    @Test
    void persistsRawEvidenceBeforeDecodingAndAcceptsTheCanonicalMeasurement() {
        prepareValidPayload();
        when(measurementStore.acceptMeasurement(
                        STAGING_ID, canonicalMeasurement(), PAYLOAD_HASH, CORRELATION_ID, NOW))
                .thenReturn(new Acceptance(MEASUREMENT_ID, false));

        IngestionResult result = ingestionService.ingestMeasurement(RAW_PAYLOAD, CORRELATION_ID);

        assertEquals(new IngestionResult(STAGING_ID, MEASUREMENT_ID, false), result);
        InOrder order = inOrder(stagingStore, payloadDecoder, measurementStore);
        order.verify(stagingStore).recordReceived(RAW_PAYLOAD, CORRELATION_ID, NOW);
        order.verify(payloadDecoder).decodeMeasurement(RAW_PAYLOAD);
        order.verify(payloadDecoder).fingerprintPayload(RAW_PAYLOAD);
        order.verify(measurementStore)
                .acceptMeasurement(
                        STAGING_ID, canonicalMeasurement(), PAYLOAD_HASH, CORRELATION_ID, NOW);
        verify(stagingStore, never()).recordRejection(any(), any(), any());
    }

    @Test
    void preservesMalformedPayloadAsARejectedStagingRecord() {
        String malformedPayload = "{broken-json";
        FactoryBridgeException decodingFailure =
                new FactoryBridgeException(ErrorCode.INVALID_PAYLOAD, "Payload is not valid JSON.");
        when(stagingStore.recordReceived(malformedPayload, CORRELATION_ID, NOW))
                .thenReturn(STAGING_ID);
        when(payloadDecoder.decodeMeasurement(malformedPayload)).thenThrow(decodingFailure);

        IngestionRejectedException rejection =
                assertThrows(
                        IngestionRejectedException.class,
                        () -> ingestionService.ingestMeasurement(malformedPayload, CORRELATION_ID));

        assertEquals(STAGING_ID, rejection.stagingId());
        assertSame(decodingFailure, rejection.failure());
        verify(stagingStore)
                .recordRejection(
                        STAGING_ID, ErrorCode.INVALID_PAYLOAD, decodingFailure.getMessage());
        verify(payloadDecoder, never()).fingerprintPayload(any());
        verifyNoInteractions(measurementStore);
    }

    @Test
    void quarantinesInvalidMeasurementWithoutAttemptingCanonicalPersistence() {
        prepareStagedDraft(draft("MES_A", SOURCE_RECORD_ID, "NaN"));

        IngestionRejectedException rejection =
                assertThrows(
                        IngestionRejectedException.class,
                        () -> ingestionService.ingestMeasurement(RAW_PAYLOAD, CORRELATION_ID));

        assertEquals(ErrorCode.INVALID_MEASUREMENT_VALUE, rejection.failure().errorCode());
        verify(stagingStore)
                .recordRejection(
                        eq(STAGING_ID), eq(ErrorCode.INVALID_MEASUREMENT_VALUE), anyString());
        verify(payloadDecoder, never()).fingerprintPayload(any());
        verifyNoInteractions(measurementStore);
    }

    @Test
    void stopsImmediatelyWhenRawEvidenceCannotBeStored() {
        FactoryBridgeException storageFailure =
                new FactoryBridgeException(
                        ErrorCode.INFRASTRUCTURE_UNAVAILABLE, "Staging database is unavailable.");
        when(stagingStore.recordReceived(RAW_PAYLOAD, CORRELATION_ID, NOW))
                .thenThrow(storageFailure);

        assertSame(
                storageFailure,
                assertThrows(
                        FactoryBridgeException.class,
                        () -> ingestionService.ingestMeasurement(RAW_PAYLOAD, CORRELATION_ID)));

        verifyNoInteractions(payloadDecoder, measurementStore);
        verify(stagingStore, never()).recordRejection(any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(
            value = ErrorCode.class,
            names = {"INFRASTRUCTURE_UNAVAILABLE", "DUPLICATE_SOURCE_RECORD"})
    void preservesCanonicalStorageFailureCodeAndItsStagingIdentity(ErrorCode errorCode) {
        prepareValidPayload();
        FactoryBridgeException storageFailure =
                new FactoryBridgeException(errorCode, errorCode.defaultMessage());
        when(measurementStore.acceptMeasurement(
                        STAGING_ID, canonicalMeasurement(), PAYLOAD_HASH, CORRELATION_ID, NOW))
                .thenThrow(storageFailure);

        IngestionRejectedException rejection =
                assertThrows(
                        IngestionRejectedException.class,
                        () -> ingestionService.ingestMeasurement(RAW_PAYLOAD, CORRELATION_ID));

        assertEquals(STAGING_ID, rejection.stagingId());
        assertSame(storageFailure, rejection.failure());
        verify(stagingStore).recordRejection(STAGING_ID, errorCode, storageFailure.getMessage());
    }

    @Test
    void retainsTheOriginalFailureWhenRecordingRejectionAlsoFails() {
        prepareStagedDraft(draft("MES_A", SOURCE_RECORD_ID, "NaN"));
        RuntimeException auditFailure = new IllegalStateException("Audit store is unavailable.");
        doThrow(auditFailure).when(stagingStore).recordRejection(any(), any(), any());

        IngestionRejectedException rejection =
                assertThrows(
                        IngestionRejectedException.class,
                        () -> ingestionService.ingestMeasurement(RAW_PAYLOAD, CORRELATION_ID));

        assertEquals(ErrorCode.INVALID_MEASUREMENT_VALUE, rejection.failure().errorCode());
        assertArrayEquals(new Throwable[] {auditFailure}, rejection.failure().getSuppressed());
        assertSame(rejection.failure(), rejection.getCause());
        assertEquals(STAGING_ID, rejection.stagingId());
        verifyNoInteractions(measurementStore);
    }

    @Test
    void returnsTheOriginalMeasurementIdentityForAnIdempotentRepeat() {
        when(stagingStore.recordReceived(RAW_PAYLOAD, CORRELATION_ID, NOW))
                .thenReturn(STAGING_ID, REPLAY_STAGING_ID);
        when(payloadDecoder.decodeMeasurement(RAW_PAYLOAD)).thenReturn(validDraft());
        when(payloadDecoder.fingerprintPayload(RAW_PAYLOAD)).thenReturn(PAYLOAD_HASH);
        when(measurementStore.acceptMeasurement(
                        any(UUID.class),
                        eq(canonicalMeasurement()),
                        eq(PAYLOAD_HASH),
                        eq(CORRELATION_ID),
                        eq(NOW)))
                .thenReturn(
                        new Acceptance(MEASUREMENT_ID, false),
                        new Acceptance(MEASUREMENT_ID, true));

        IngestionResult first = ingestionService.ingestMeasurement(RAW_PAYLOAD, CORRELATION_ID);
        IngestionResult repeat = ingestionService.ingestMeasurement(RAW_PAYLOAD, CORRELATION_ID);

        assertFalse(first.duplicate());
        assertTrue(repeat.duplicate());
        assertEquals(first.measurementId(), repeat.measurementId());
        assertNotEquals(first.stagingId(), repeat.stagingId());
        verify(stagingStore, never()).recordRejection(any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"MES_B,MES-A-20260910-000001", "MES_A,another-record"})
    void quarantinesFetchedIdentityMismatchAfterPreservingTheRawResponse(
            String actualSource, String actualRecord) {
        prepareFetchedDraft(draft(actualSource, actualRecord, "95.36"));

        IngestionRejectedException rejection =
                assertThrows(
                        IngestionRejectedException.class,
                        () ->
                                ingestionService.ingestFetchedMeasurement(
                                        RAW_PAYLOAD, CORRELATION_ID, "MES_A", SOURCE_RECORD_ID));

        assertEquals(ErrorCode.EXTERNAL_RECORD_MISMATCH, rejection.failure().errorCode());
        assertEquals(STAGING_ID, rejection.stagingId());
        InOrder order = inOrder(stagingStore, payloadDecoder);
        order.verify(stagingStore)
                .recordReceived(RAW_PAYLOAD, CORRELATION_ID, NOW, EXPECTED_SOURCE);
        order.verify(payloadDecoder).decodeMeasurement(RAW_PAYLOAD);
        order.verify(stagingStore)
                .recordRejection(
                        eq(STAGING_ID), eq(ErrorCode.EXTERNAL_RECORD_MISMATCH), anyString());
        verifyNoInteractions(measurementStore);
        verify(payloadDecoder, never()).fingerprintPayload(any());
    }

    @Test
    void verifiesFetchedIdentityAgainstTheCleanedCanonicalSource() {
        prepareFetchedDraft(draft(" mes_a ", " " + SOURCE_RECORD_ID + " ", "95.36"));
        when(payloadDecoder.fingerprintPayload(RAW_PAYLOAD)).thenReturn(PAYLOAD_HASH);
        when(measurementStore.acceptMeasurement(
                        STAGING_ID, canonicalMeasurement(), PAYLOAD_HASH, CORRELATION_ID, NOW))
                .thenReturn(new Acceptance(MEASUREMENT_ID, false));

        IngestionResult result =
                ingestionService.ingestFetchedMeasurement(
                        RAW_PAYLOAD, CORRELATION_ID, "MES_A", SOURCE_RECORD_ID);

        assertEquals(MEASUREMENT_ID, result.measurementId());
    }

    @Test
    void classifiesWrongFetchedIdentityBeforeAnUnsupportedUnitCanHideIt() {
        prepareFetchedDraft(draft("MES_A", "another-record", "95.36", "KELVIN"));

        IngestionRejectedException rejection =
                assertThrows(
                        IngestionRejectedException.class,
                        () ->
                                ingestionService.ingestFetchedMeasurement(
                                        RAW_PAYLOAD, CORRELATION_ID, "MES_A", SOURCE_RECORD_ID));

        // 此狀態禁止一般 staging replay；未來擴充單位白名單也不能放行原本錯誤的來源識別。
        assertEquals(ErrorCode.EXTERNAL_RECORD_MISMATCH, rejection.failure().errorCode());
        verify(stagingStore)
                .recordRejection(
                        eq(STAGING_ID), eq(ErrorCode.EXTERNAL_RECORD_MISMATCH), anyString());
        verifyNoInteractions(measurementStore);
    }

    @ParameterizedTest
    @MethodSource("invalidFetchedIdentities")
    void quarantinesMissingIdentityAndPreservesCaseSensitivityOfSourceRecordIds(
            String source, String recordId) {
        prepareFetchedDraft(draft(source, recordId, "95.36"));

        IngestionRejectedException rejection =
                assertThrows(
                        IngestionRejectedException.class,
                        () ->
                                ingestionService.ingestFetchedMeasurement(
                                        RAW_PAYLOAD, CORRELATION_ID, "MES_A", SOURCE_RECORD_ID));

        assertEquals(ErrorCode.EXTERNAL_RECORD_MISMATCH, rejection.failure().errorCode());
        assertEquals(STAGING_ID, rejection.stagingId());
        verifyNoInteractions(measurementStore);
    }

    private static Stream<Arguments> invalidFetchedIdentities() {
        return Stream.of(
                Arguments.of(null, SOURCE_RECORD_ID),
                Arguments.of("MES_A", null),
                Arguments.of("MES_A", "mes-a-20260910-000001"));
    }

    @Test
    void retainsStagingIdentityAndTheOriginalCauseWhenTheDecoderHasAProgrammingDefect() {
        when(stagingStore.recordReceived(RAW_PAYLOAD, CORRELATION_ID, NOW)).thenReturn(STAGING_ID);
        RuntimeException defect =
                new IllegalStateException("Unexpected decoder implementation detail.");
        when(payloadDecoder.decodeMeasurement(RAW_PAYLOAD)).thenThrow(defect);

        IngestionRejectedException rejection =
                assertThrows(
                        IngestionRejectedException.class,
                        () -> ingestionService.ingestMeasurement(RAW_PAYLOAD, CORRELATION_ID));

        assertEquals(STAGING_ID, rejection.stagingId());
        assertEquals(ErrorCode.INTERNAL_ERROR, rejection.failure().errorCode());
        assertEquals(ErrorCode.INTERNAL_ERROR.defaultMessage(), rejection.failure().getMessage());
        assertSame(defect, rejection.failure().getCause());
        verify(stagingStore)
                .recordRejection(
                        STAGING_ID,
                        ErrorCode.INTERNAL_ERROR,
                        ErrorCode.INTERNAL_ERROR.defaultMessage());
        verifyNoInteractions(measurementStore);
    }

    @Test
    void preservesUnexpectedStorageFailureAndAnyAdditionalAuditFailure() {
        prepareValidPayload();
        RuntimeException storageDefect =
                new IllegalStateException("Unexpected persistence invariant failure.");
        when(measurementStore.acceptMeasurement(
                        STAGING_ID, canonicalMeasurement(), PAYLOAD_HASH, CORRELATION_ID, NOW))
                .thenThrow(storageDefect);
        RuntimeException auditFailure = new IllegalStateException("Audit connection lost.");
        doThrow(auditFailure).when(stagingStore).recordRejection(any(), any(), any());

        IngestionRejectedException rejection =
                assertThrows(
                        IngestionRejectedException.class,
                        () -> ingestionService.ingestMeasurement(RAW_PAYLOAD, CORRELATION_ID));

        assertEquals(STAGING_ID, rejection.stagingId());
        assertEquals(ErrorCode.INTERNAL_ERROR, rejection.failure().errorCode());
        assertSame(storageDefect, rejection.failure().getCause());
        assertArrayEquals(new Throwable[] {auditFailure}, rejection.failure().getSuppressed());
    }

    @Test
    void recordsExpectedIdentityWithTheRawResponseEvenWhenMismatchAuditFails() {
        prepareFetchedDraft(draft("MES_B", "unexpected-record", "95.36"));
        RuntimeException auditFailure =
                new IllegalStateException("Audit connection lost after initial staging commit.");
        doThrow(auditFailure).when(stagingStore).recordRejection(any(), any(), any());

        IngestionRejectedException rejection =
                assertThrows(
                        IngestionRejectedException.class,
                        () ->
                                ingestionService.ingestFetchedMeasurement(
                                        RAW_PAYLOAD, CORRELATION_ID, "MES_A", SOURCE_RECORD_ID));

        assertEquals(STAGING_ID, rejection.stagingId());
        assertEquals(ErrorCode.EXTERNAL_RECORD_MISMATCH, rejection.failure().errorCode());
        assertArrayEquals(new Throwable[] {auditFailure}, rejection.failure().getSuppressed());
        InOrder order = inOrder(stagingStore, payloadDecoder);
        order.verify(stagingStore)
                .recordReceived(RAW_PAYLOAD, CORRELATION_ID, NOW, EXPECTED_SOURCE);
        order.verify(payloadDecoder).decodeMeasurement(RAW_PAYLOAD);
        verifyNoInteractions(measurementStore);
    }

    private void prepareValidPayload() {
        prepareStagedDraft(validDraft());
        when(payloadDecoder.fingerprintPayload(RAW_PAYLOAD)).thenReturn(PAYLOAD_HASH);
    }

    private void prepareStagedDraft(MeasurementDraft draft) {
        when(stagingStore.recordReceived(RAW_PAYLOAD, CORRELATION_ID, NOW)).thenReturn(STAGING_ID);
        when(payloadDecoder.decodeMeasurement(RAW_PAYLOAD)).thenReturn(draft);
    }

    private void prepareFetchedDraft(MeasurementDraft draft) {
        when(stagingStore.recordReceived(RAW_PAYLOAD, CORRELATION_ID, NOW, EXPECTED_SOURCE))
                .thenReturn(STAGING_ID);
        when(payloadDecoder.decodeMeasurement(RAW_PAYLOAD)).thenReturn(draft);
    }
}
