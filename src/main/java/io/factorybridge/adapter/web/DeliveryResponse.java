package io.factorybridge.adapter.web;

import io.factorybridge.application.model.Delivery;
import java.time.Instant;
import java.util.UUID;

public record DeliveryResponse(
        UUID id,
        UUID measurementId,
        String destination,
        String status,
        int attemptCount,
        int totalAttempts,
        int replayCount,
        Instant nextAttemptAt,
        String correlationId,
        String lastErrorCode,
        String lastErrorMessage) {
    static DeliveryResponse fromDelivery(Delivery delivery) {
        return new DeliveryResponse(
                delivery.id(),
                delivery.measurementId(),
                delivery.destination().name(),
                delivery.status(),
                delivery.attemptCount(),
                delivery.totalAttempts(),
                delivery.replayCount(),
                delivery.nextAttemptAt(),
                delivery.correlationId(),
                delivery.lastErrorCode(),
                delivery.lastErrorMessage());
    }
}
