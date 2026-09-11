package io.factorybridge.adapter.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.factorybridge.application.*;
import io.factorybridge.application.model.*;
import io.factorybridge.application.port.*;
import io.factorybridge.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({
    MeasurementController.class,
    ImportController.class,
    StagingController.class,
    DeliveryController.class
})
class MeasurementApiTest {
    private static final UUID STAGING_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEASUREMENT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String CORRELATION_ID = "interview-request-001";
    @Autowired MockMvc mvc;
    @MockitoBean MeasurementIngestionService ingestion;
    @MockitoBean ExternalMeasurementImportService imports;
    @MockitoBean StagingReplayService stagingReplay;
    @MockitoBean DeliveryDispatcher dispatcher;
    @MockitoBean MeasurementStore measurements;
    @MockitoBean StagingStore staging;
    @MockitoBean DeliveryStore deliveries;

    @TestConfiguration
    static class TestClock {
        @Bean
        Clock systemClock() {
            return Clock.fixed(Instant.parse("2026-09-10T06:30:22Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void acceptedResponseHasLocationAndTraceableReceipt() throws Exception {
        when(ingestion.ingestMeasurement("{}", CORRELATION_ID))
                .thenReturn(new IngestionResult(STAGING_ID, MEASUREMENT_ID, false));
        mvc.perform(
                        post("/api/v1/measurements")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                                .header(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/measurements/" + MEASUREMENT_ID))
                .andExpect(header().string(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.stagingId").value(STAGING_ID.toString()))
                .andExpect(
                        jsonPath("$.deliveriesUrl")
                                .value("/api/v1/measurements/" + MEASUREMENT_ID + "/deliveries"));
    }

    @Test
    void identicalResendReturns200AndOriginalMeasurement() throws Exception {
        when(ingestion.ingestMeasurement(anyString(), anyString()))
                .thenReturn(new IngestionResult(STAGING_ID, MEASUREMENT_ID, true));
        mvc.perform(
                        post("/api/v1/measurements")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"))
                .andExpect(jsonPath("$.measurementId").value(MEASUREMENT_ID.toString()));
    }

    @Test
    void validationErrorContainsStagingAndStableBusinessCode() throws Exception {
        when(ingestion.ingestMeasurement(anyString(), anyString()))
                .thenThrow(
                        new IngestionRejectedException(
                                STAGING_ID,
                                new FactoryBridgeException(
                                        ErrorCode.INVALID_EVENT_TIME,
                                        "Measurement eventTime is invalid.")));
        mvc.perform(
                        post("/api/v1/measurements")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                                .header(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INVALID_EVENT_TIME"))
                .andExpect(jsonPath("$.category").value("VALIDATION"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID))
                .andExpect(jsonPath("$.stagingId").value(STAGING_ID.toString()))
                .andExpect(jsonPath("$.timestamp").value("2026-09-10T06:30:22Z"));
    }

    @Test
    void changedSourceContentIs409() throws Exception {
        when(ingestion.ingestMeasurement(anyString(), anyString()))
                .thenThrow(
                        new IngestionRejectedException(
                                STAGING_ID,
                                new FactoryBridgeException(
                                        ErrorCode.DUPLICATE_SOURCE_RECORD,
                                        "Source record content differs.")));
        mvc.perform(
                        post("/api/v1/measurements")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("BUSINESS"));
    }

    @Test
    void unavailableDatabaseIs503AndDoesNotExposeSql() throws Exception {
        when(ingestion.ingestMeasurement(anyString(), anyString()))
                .thenThrow(
                        new org.springframework.dao.DataAccessResourceFailureException(
                                "password=secret"));
        var result =
                mvc.perform(
                                post("/api/v1/measurements")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{}"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.errorCode").value("INFRASTRUCTURE_UNAVAILABLE"))
                        .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret");
    }

    @Test
    void unexpectedFailureHasSafe500ErrorEnvelope() throws Exception {
        when(ingestion.ingestMeasurement(anyString(), anyString()))
                .thenThrow(new IllegalStateException("secret-internal-detail"));
        var result =
                mvc.perform(
                                post("/api/v1/measurements")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{}"))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                        .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("secret-internal-detail");
    }

    @Test
    void oversizedAndChunkedBodiesAreRejectedBeforeIngestion() throws Exception {
        mvc.perform(
                        post("/api/v1/measurements")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("a".repeat(65_537)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PAYLOAD"));
        verifyNoInteractions(ingestion);
    }

    @Test
    void invalidUtf8CannotBeSilentlyReplaced() throws Exception {
        mvc.perform(
                        post("/api/v1/measurements")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(new byte[] {(byte) 0xC3, 0x28}))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PAYLOAD"));
        verifyNoInteractions(ingestion);
    }

    @Test
    void malformedJsonStillReachesStagingBoundary() throws Exception {
        when(ingestion.ingestMeasurement(anyString(), anyString()))
                .thenThrow(
                        new IngestionRejectedException(
                                STAGING_ID,
                                new FactoryBridgeException(
                                        ErrorCode.INVALID_PAYLOAD, "Malformed JSON.")));
        mvc.perform(
                        post("/api/v1/measurements")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.stagingId").value(STAGING_ID.toString()));
        verify(ingestion).ingestMeasurement(eq("{invalid"), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "invalid"})
    void listLimitsAreValidated(String limit) throws Exception {
        mvc.perform(get("/api/v1/measurements").param("limit", limit))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(measurements);
    }

    @Test
    void beanValidationRunsBeforeImport() throws Exception {
        mvc.perform(
                        post("/api/v1/imports")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sourceSystem\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations.length()").value(2));
        verifyNoInteractions(imports);
    }

    @Test
    void invalidUuidIs400() throws Exception {
        mvc.perform(get("/api/v1/measurements/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void absentMeasurementIs404() throws Exception {
        when(measurements.findMeasurement(MEASUREMENT_ID)).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/measurements/" + MEASUREMENT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RECORD_NOT_FOUND"));
    }

    @Test
    void unsupportedContentTypeRetains415() throws Exception {
        mvc.perform(post("/api/v1/measurements").contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    void unsupportedMethodRetains405() throws Exception {
        mvc.perform(delete("/api/v1/measurements/" + MEASUREMENT_ID))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    void unknownPathRetains404() throws Exception {
        mvc.perform(get("/api/v1/unknown")).andExpect(status().isNotFound());
    }

    @Test
    void untrustedCorrelationIdIsReplaced() throws Exception {
        when(measurements.listMeasurements(20)).thenReturn(List.of());
        var result =
                mvc.perform(
                                get("/api/v1/measurements")
                                        .header(
                                                CorrelationIdFilter.HEADER,
                                                "unsafe user-controlled id"))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThatCode(
                        () ->
                                UUID.fromString(
                                        result.getResponse().getHeader(CorrelationIdFilter.HEADER)))
                .doesNotThrowAnyException();
    }

    @Test
    void activeDeliveryCannotBeManuallyReplayed() throws Exception {
        when(dispatcher.replayDeadDelivery(MEASUREMENT_ID))
                .thenThrow(
                        new FactoryBridgeException(
                                ErrorCode.DELIVERY_NOT_REPLAYABLE,
                                "Only DEAD deliveries can be replayed."));
        mvc.perform(post("/api/v1/deliveries/" + MEASUREMENT_ID + "/replays"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DELIVERY_NOT_REPLAYABLE"));
    }

    @Test
    void leaseTokenNeverLeaksThroughDeliveryApi() throws Exception {
        UUID token = UUID.randomUUID();
        when(deliveries.findDelivery(MEASUREMENT_ID))
                .thenReturn(
                        Optional.of(
                                new Delivery(
                                        MEASUREMENT_ID,
                                        MEASUREMENT_ID,
                                        Destination.DOWNSTREAM,
                                        "IN_FLIGHT",
                                        1,
                                        1,
                                        0,
                                        Instant.now(),
                                        token,
                                        CORRELATION_ID,
                                        null,
                                        null)));
        var result =
                mvc.perform(get("/api/v1/deliveries/" + MEASUREMENT_ID))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.leaseToken").doesNotExist())
                        .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(token.toString());
    }
}
