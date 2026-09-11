package io.factorybridge.application.model;

import java.util.UUID;

public record Acceptance(UUID measurementId, boolean duplicate) {}
