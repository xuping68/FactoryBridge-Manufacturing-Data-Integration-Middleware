package io.factorybridge.application.model;

import java.time.Instant;
import java.util.UUID;

// leaseToken 僅供 worker fencing，API 不直接回傳此 record。
public record Delivery(
        UUID id,
        UUID measurementId,
        Destination destination,
        String status,
        int attemptCount,
        int totalAttempts,
        int replayCount,
        Instant nextAttemptAt,
        UUID leaseToken,
        String correlationId,
        String lastErrorCode,
        String lastErrorMessage) {}
