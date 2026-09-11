package io.factorybridge.application.model;

import io.factorybridge.domain.CanonicalMeasurement;
import java.time.Instant;
import java.util.UUID;

public record StoredMeasurement(UUID id, CanonicalMeasurement measurement, Instant createdAt) {}
