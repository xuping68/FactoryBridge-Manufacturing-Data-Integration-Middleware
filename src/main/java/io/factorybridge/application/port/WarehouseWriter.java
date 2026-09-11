package io.factorybridge.application.port;

import io.factorybridge.domain.CanonicalMeasurement;
import java.util.UUID;

public interface WarehouseWriter {
    void writeMeasurement(UUID measurementId, CanonicalMeasurement measurement);
}
