package io.factorybridge.application.port;

import io.factorybridge.application.model.*;
import io.factorybridge.domain.CanonicalMeasurement;
import java.time.Instant;
import java.util.*;

public interface MeasurementStore {
    // canonical、outbox、staging 狀態須在單一交易完成；不同內容的相同來源識別須拒絕。
    Acceptance acceptMeasurement(
            UUID stagingId,
            CanonicalMeasurement measurement,
            String payloadHash,
            String correlationId,
            Instant acceptedAt);

    Optional<StoredMeasurement> findMeasurement(UUID measurementId);

    List<StoredMeasurement> listMeasurements(int limit);
}
