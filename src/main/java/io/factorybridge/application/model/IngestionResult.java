package io.factorybridge.application.model;

import java.util.UUID;

public record IngestionResult(UUID stagingId, UUID measurementId, boolean duplicate) {}
