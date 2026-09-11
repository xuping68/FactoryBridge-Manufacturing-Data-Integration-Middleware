package io.factorybridge.application.model;

import java.time.Instant;
import java.util.UUID;

public record StagingRecord(
        UUID id,
        String rawPayload,
        String correlationId,
        Instant receivedAt,
        String status,
        UUID measurementId,
        String errorCode,
        String errorMessage,
        SourceRecordIdentity expectedSource) {}
