package io.factorybridge.application.port;

import io.factorybridge.domain.CanonicalMeasurement;
import java.util.UUID;

public interface DownstreamClient {
    void deliverMeasurement(
            UUID deliveryId,
            UUID measurementId,
            CanonicalMeasurement measurement,
            String correlationId);
}
