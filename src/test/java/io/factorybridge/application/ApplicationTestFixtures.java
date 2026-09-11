package io.factorybridge.application;

import io.factorybridge.application.model.Delivery;
import io.factorybridge.application.model.Destination;
import io.factorybridge.application.model.SourceRecordIdentity;
import io.factorybridge.application.model.StoredMeasurement;
import io.factorybridge.domain.CanonicalMeasurement;
import io.factorybridge.domain.MeasurementDraft;
import io.factorybridge.domain.MeasurementNormalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** 固定時間與識別碼讓失敗流程可重現；真正的正規化規則不以 mock 取代。 */
final class ApplicationTestFixtures {
    static final Instant NOW = Instant.parse("2026-09-10T07:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final UUID STAGING_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID REPLAY_STAGING_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final UUID MEASUREMENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID DELIVERY_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    static final UUID LEASE_TOKEN = UUID.fromString("55555555-5555-4555-8555-555555555555");
    static final String CORRELATION_ID = "interview-demo-001";
    static final String SOURCE_RECORD_ID = "MES-A-20260910-000001";
    static final SourceRecordIdentity EXPECTED_SOURCE =
            new SourceRecordIdentity("MES_A", SOURCE_RECORD_ID);
    static final String PAYLOAD_HASH = "a".repeat(64);
    static final String RAW_PAYLOAD =
            """
            {"sourceSystem":"MES_A","sourceRecordId":"MES-A-20260910-000001",
             "plantCode":"KH01","productionLine":"CELL-LINE-01","stationCode":"COATING-03",
             "equipmentId":"EQ-CT-003","metricCode":"TEMP","value":"95.36","unit":"F",
             "eventTime":"2026/09/10 14:30:22","qualityStatus":"OK"}
            """;

    private ApplicationTestFixtures() {}

    static MeasurementDraft validDraft() {
        return draft("MES_A", SOURCE_RECORD_ID, "95.36");
    }

    static MeasurementDraft draft(String source, String recordId, String value) {
        return draft(source, recordId, value, "F");
    }

    static MeasurementDraft draft(String source, String recordId, String value, String unit) {
        return new MeasurementDraft(
                source,
                recordId,
                "KH01",
                "CELL-LINE-01",
                "COATING-03",
                "EQ-CT-003",
                null,
                null,
                null,
                "TEMP",
                null,
                value,
                unit,
                "2026/09/10 14:30:22",
                "OK",
                null,
                null);
    }

    static CanonicalMeasurement canonicalMeasurement() {
        return new MeasurementNormalizer().normalizeMeasurement(validDraft());
    }

    static StoredMeasurement storedMeasurement() {
        return new StoredMeasurement(MEASUREMENT_ID, canonicalMeasurement(), NOW);
    }

    static Delivery claimedDelivery(Destination destination, int attemptCount) {
        return new Delivery(
                DELIVERY_ID,
                MEASUREMENT_ID,
                destination,
                "IN_FLIGHT",
                attemptCount,
                attemptCount,
                0,
                NOW,
                LEASE_TOKEN,
                CORRELATION_ID,
                null,
                null);
    }
}
