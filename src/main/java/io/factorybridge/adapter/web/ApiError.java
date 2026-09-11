package io.factorybridge.adapter.web;

import io.factorybridge.domain.*;
import java.time.Instant;
import java.util.*;

public record ApiError(
        ErrorCode errorCode,
        ErrorCategory category,
        String message,
        String correlationId,
        Instant timestamp,
        UUID stagingId,
        List<FieldViolation> violations) {
    public record FieldViolation(String field, String message) {}
}
