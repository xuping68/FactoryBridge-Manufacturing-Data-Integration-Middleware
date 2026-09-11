package io.factorybridge.application;

import static io.factorybridge.application.ApplicationTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import io.factorybridge.application.model.IngestionResult;
import io.factorybridge.application.port.ExternalDataClient;
import io.factorybridge.domain.ErrorCode;
import io.factorybridge.domain.FactoryBridgeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExternalMeasurementImportServiceTest {
    @Mock private ExternalDataClient externalDataClient;
    @Mock private MeasurementIngestionService ingestionService;
    private ExternalMeasurementImportService importService;

    @BeforeEach
    void createImportService() {
        importService = new ExternalMeasurementImportService(externalDataClient, ingestionService);
    }

    @Test
    void fetchesTheRequestedRecordAndPassesItsExpectedIdentityToIngestion() {
        String recordId = "Source-Mixed-Case-001";
        IngestionResult expected = new IngestionResult(STAGING_ID, MEASUREMENT_ID, false);
        when(externalDataClient.fetchMeasurement("MES_A", recordId)).thenReturn(RAW_PAYLOAD);
        when(ingestionService.ingestFetchedMeasurement(
                        RAW_PAYLOAD, CORRELATION_ID, "MES_A", recordId))
                .thenReturn(expected);

        IngestionResult actual =
                importService.importMeasurement(" mes_a ", " " + recordId + " ", CORRELATION_ID);

        assertEquals(expected, actual);
        InOrder order = inOrder(externalDataClient, ingestionService);
        order.verify(externalDataClient).fetchMeasurement("MES_A", recordId);
        order.verify(ingestionService)
                .ingestFetchedMeasurement(RAW_PAYLOAD, CORRELATION_ID, "MES_A", recordId);
        order.verifyNoMoreInteractions();
    }

    @Test
    void doesNotFabricateAnIngestionWhenTheExternalSourceCannotBeRead() {
        FactoryBridgeException sourceFailure =
                new FactoryBridgeException(
                        ErrorCode.EXTERNAL_SOURCE_UNAVAILABLE,
                        "External source request timed out.");
        when(externalDataClient.fetchMeasurement("MES_A", SOURCE_RECORD_ID))
                .thenThrow(sourceFailure);

        assertSame(
                sourceFailure,
                assertThrows(
                        FactoryBridgeException.class,
                        () ->
                                importService.importMeasurement(
                                        "MES_A", SOURCE_RECORD_ID, CORRELATION_ID)));

        verifyNoInteractions(ingestionService);
    }

    @Test
    void preservesTheRejectedFetchedResponseAndItsStagingIdentifier() {
        FactoryBridgeException mismatch =
                new FactoryBridgeException(
                        ErrorCode.EXTERNAL_RECORD_MISMATCH,
                        "External response identity does not match the requested record.");
        IngestionRejectedException rejection = new IngestionRejectedException(STAGING_ID, mismatch);
        when(externalDataClient.fetchMeasurement("MES_A", SOURCE_RECORD_ID))
                .thenReturn(RAW_PAYLOAD);
        when(ingestionService.ingestFetchedMeasurement(
                        RAW_PAYLOAD, CORRELATION_ID, "MES_A", SOURCE_RECORD_ID))
                .thenThrow(rejection);

        IngestionRejectedException observed =
                assertThrows(
                        IngestionRejectedException.class,
                        () ->
                                importService.importMeasurement(
                                        "MES_A", SOURCE_RECORD_ID, CORRELATION_ID));

        assertSame(rejection, observed);
        assertEquals(STAGING_ID, observed.stagingId());
        assertSame(mismatch, observed.failure());
    }
}
