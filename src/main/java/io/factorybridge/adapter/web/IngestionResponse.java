package io.factorybridge.adapter.web;

import io.factorybridge.application.model.IngestionResult;
import java.util.UUID;

public record IngestionResponse(
        UUID stagingId,
        UUID measurementId,
        String status,
        String correlationId,
        String deliveriesUrl) {
    static IngestionResponse fromResult(IngestionResult result, String correlationId) {
        return new IngestionResponse(
                result.stagingId(),
                result.measurementId(),
                result.duplicate() ? "DUPLICATE" : "ACCEPTED",
                correlationId,
                "/api/v1/measurements/" + result.measurementId() + "/deliveries");
    }
}
