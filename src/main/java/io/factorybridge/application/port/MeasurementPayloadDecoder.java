package io.factorybridge.application.port;

import io.factorybridge.domain.MeasurementDraft;

public interface MeasurementPayloadDecoder {
    MeasurementDraft decodeMeasurement(String rawPayload);

    String fingerprintPayload(String rawPayload);
}
