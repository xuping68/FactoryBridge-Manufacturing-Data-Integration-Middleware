package io.factorybridge.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.factorybridge.application.model.Acceptance;
import io.factorybridge.application.model.Destination;
import io.factorybridge.application.model.SourceRecordIdentity;
import io.factorybridge.domain.ErrorCode;
import io.factorybridge.domain.FactoryBridgeException;
import io.factorybridge.domain.QualityStatus;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

class MeasurementPersistenceIT extends PostgresIntegrationSupport {
    @Test
    void bootstrapsFlywayAndValidatesJpaMappingsAgainstPostgres() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM flyway_schema_history WHERE success",
                                Long.class))
                .isPositive();
        Acceptance accepted = accept("migration-check", QualityStatus.GOOD);
        assertThat(
                        measurements
                                .findMeasurement(accepted.measurementId())
                                .orElseThrow()
                                .measurement())
                .isEqualTo(measurement("migration-check", QualityStatus.GOOD));
    }

    @Test
    void preservesMalformedRawPayloadAndRejectionAfterCallerTransactionRollsBack() {
        String malformed = "{\"value\": this is not JSON\n";
        UUID[] stagingId = new UUID[1];
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        transaction -> {
                            stagingId[0] = staging.recordReceived(malformed, CORRELATION_ID, NOW);
                            staging.recordRejection(
                                    stagingId[0],
                                    ErrorCode.INVALID_PAYLOAD,
                                    "JSON could not be decoded.");
                            transaction.setRollbackOnly();
                        });
        var rejected = staging.findStagingRecord(stagingId[0]).orElseThrow();
        assertThat(rejected.rawPayload()).isEqualTo(malformed);
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.errorCode()).isEqualTo("INVALID_PAYLOAD");
    }

    @Test
    void repeatedIdenticalSourceRecordsReuseCanonicalIdWithoutAdditionalDeliveries() {
        Acceptance first = accept("same-source-record", QualityStatus.GOOD);
        UUID repeatedStagingId = receiveRawPayload();
        Acceptance second =
                measurements.acceptMeasurement(
                        repeatedStagingId,
                        measurement("same-source-record", QualityStatus.GOOD),
                        HASH,
                        CORRELATION_ID,
                        NOW);
        Acceptance third = accept("same-source-record", QualityStatus.GOOD);

        assertThat(first.duplicate()).isFalse();
        assertThat(second).isEqualTo(new Acceptance(first.measurementId(), true));
        assertThat(third).isEqualTo(second);
        assertThat(canonicalCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(2);
        assertThat(staging.findStagingRecord(repeatedStagingId).orElseThrow().status())
                .isEqualTo("DUPLICATE");
    }

    @Test
    void preservesActualNulAndUnicodeInMalformedJsonAsUtf8Bytes() {
        String malformed = "{\"設備\":\"量測" + (char) 0 + "資料\"}";
        UUID stagingId = staging.recordReceived(malformed, CORRELATION_ID, NOW);
        staging.recordRejection(
                stagingId, ErrorCode.INVALID_PAYLOAD, "Unescaped control character in JSON.");

        assertThat(staging.findStagingRecord(stagingId).orElseThrow().rawPayload())
                .isEqualTo(malformed);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT raw_payload FROM factorybridge.staging_record WHERE id = ?",
                                byte[].class,
                                stagingId))
                .containsExactly(malformed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void commitsExpectedSourceWithRawBeforeOutcomeEvenWhenCallerRollsBack() {
        SourceRecordIdentity expected = new SourceRecordIdentity("MES_A", "requested-record");
        String mismatchedRaw =
                "{\"sourceSystem\":\"MES_B\",\"sourceRecordId\":\"returned-record\"}";
        UUID[] stagingId = new UUID[1];
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        transaction -> {
                            stagingId[0] =
                                    staging.recordReceived(
                                            mismatchedRaw, CORRELATION_ID, NOW, expected);
                            // 模擬驗證／寫 outcome 之前中斷：原請求條件不能依賴最後錯誤才存在。
                            transaction.setRollbackOnly();
                        });

        var received = staging.findStagingRecord(stagingId[0]).orElseThrow();
        assertThat(received.rawPayload()).isEqualTo(mismatchedRaw);
        assertThat(received.expectedSource()).isEqualTo(expected);
        assertThat(received.status()).isEqualTo("RECEIVED");
        assertThat(received.errorCode()).isNull();
    }

    @Test
    void retainsExpectedSourceAndRawWhenRecordingRejectionFails() {
        SourceRecordIdentity expected =
                new SourceRecordIdentity("MES_A", "expected-before-audit-failure");
        String raw = "{\"sourceSystem\":\"MES_B\"}";
        UUID stagingId = staging.recordReceived(raw, CORRELATION_ID, NOW, expected);
        jdbc.execute(
                """
                ALTER TABLE factorybridge.staging_record ADD CONSTRAINT test_rejection_failure
                CHECK (status <> 'REJECTED')
                """);
        try {
            assertThatThrownBy(
                            () ->
                                    staging.recordRejection(
                                            stagingId,
                                            ErrorCode.EXTERNAL_RECORD_MISMATCH,
                                            "External response identity does not match the request."))
                    .isInstanceOfSatisfying(
                            FactoryBridgeException.class,
                            exception ->
                                    assertThat(exception.errorCode())
                                            .isEqualTo(ErrorCode.INFRASTRUCTURE_UNAVAILABLE));

            var received = staging.findStagingRecord(stagingId).orElseThrow();
            assertThat(received.status()).isEqualTo("RECEIVED");
            assertThat(received.errorCode()).isNull();
            assertThat(received.rawPayload()).isEqualTo(raw);
            assertThat(received.expectedSource()).isEqualTo(expected);
        } finally {
            jdbc.execute(
                    "ALTER TABLE factorybridge.staging_record DROP CONSTRAINT test_rejection_failure");
        }
    }

    @Test
    void databaseRejectsHalfAnExpectedSourceIdentityAndPushDefaultsToNoExpectation() {
        UUID stagingId = receiveRawPayload();
        assertThat(staging.findStagingRecord(stagingId).orElseThrow().expectedSource()).isNull();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE factorybridge.staging_record SET expected_source = 'MES_A' WHERE id = ?",
                                        stagingId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE factorybridge.staging_record SET expected_source_record_id = 'half-identity' WHERE id = ?",
                                        stagingId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsSameSourceKeyWithDifferentFingerprintWithoutChangingAcceptedData() {
        Acceptance first = accept("conflicting-source-record", QualityStatus.GOOD);
        UUID conflictStaging = receiveRawPayload();

        assertThatThrownBy(
                        () ->
                                measurements.acceptMeasurement(
                                        conflictStaging,
                                        measurement(
                                                "conflicting-source-record", QualityStatus.GOOD),
                                        "b".repeat(64),
                                        CORRELATION_ID,
                                        NOW))
                .isInstanceOfSatisfying(
                        FactoryBridgeException.class,
                        exception ->
                                assertThat(exception.errorCode())
                                        .isEqualTo(ErrorCode.DUPLICATE_SOURCE_RECORD));

        assertThat(canonicalCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(2);
        assertThat(measurements.findMeasurement(first.measurementId())).isPresent();
        assertThat(staging.findStagingRecord(conflictStaging).orElseThrow().status())
                .isEqualTo("RECEIVED");
    }

    @Test
    void concurrentIdenticalSourceRecordsCommitExactlyOneCanonicalMeasurement() throws Exception {
        UUID firstStaging = receiveRawPayload();
        UUID secondStaging = receiveRawPayload();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first =
                    executor.submit(
                            () -> {
                                start.await();
                                return measurements.acceptMeasurement(
                                        firstStaging,
                                        measurement("concurrent", QualityStatus.GOOD),
                                        HASH,
                                        CORRELATION_ID,
                                        NOW);
                            });
            var second =
                    executor.submit(
                            () -> {
                                start.await();
                                return measurements.acceptMeasurement(
                                        secondStaging,
                                        measurement("concurrent", QualityStatus.GOOD),
                                        HASH,
                                        CORRELATION_ID,
                                        NOW);
                            });
            start.countDown();
            List<Acceptance> results =
                    List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results)
                    .extracting(Acceptance::measurementId)
                    .containsOnly(results.getFirst().measurementId());
            assertThat(results)
                    .extracting(Acceptance::duplicate)
                    .containsExactlyInAnyOrder(false, true);
        }
        assertThat(canonicalCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(2);
    }

    @Test
    void rollsBackCanonicalOutboxAndStagingAcceptanceWhenSecondDestinationCannotBeEnqueued() {
        UUID stagingId = receiveRawPayload();
        // 真實 DB constraint 故障，驗證第一個目的地已插入後，整筆交易仍完整回滾。
        jdbc.execute(
                """
                ALTER TABLE factorybridge.delivery_outbox ADD CONSTRAINT test_reject_downstream
                CHECK (destination <> 'DOWNSTREAM')
                """);
        try {
            assertThatThrownBy(
                            () ->
                                    measurements.acceptMeasurement(
                                            stagingId,
                                            measurement("rollback-record", QualityStatus.GOOD),
                                            HASH,
                                            CORRELATION_ID,
                                            NOW))
                    .isInstanceOfSatisfying(
                            FactoryBridgeException.class,
                            exception ->
                                    assertThat(exception.errorCode())
                                            .isEqualTo(ErrorCode.INFRASTRUCTURE_UNAVAILABLE));
            assertThat(canonicalCount()).isZero();
            assertThat(outboxCount()).isZero();
            assertThat(staging.findStagingRecord(stagingId).orElseThrow().status())
                    .isEqualTo("RECEIVED");
        } finally {
            jdbc.execute(
                    "ALTER TABLE factorybridge.delivery_outbox DROP CONSTRAINT test_reject_downstream");
        }
    }

    @Test
    void lateRejectionCannotOverwriteSuccessfulAcceptance() {
        UUID stagingId = receiveRawPayload();
        Acceptance accepted =
                measurements.acceptMeasurement(
                        stagingId,
                        measurement("accepted-before-error", QualityStatus.GOOD),
                        HASH,
                        CORRELATION_ID,
                        NOW);
        staging.recordRejection(
                stagingId, ErrorCode.INFRASTRUCTURE_UNAVAILABLE, "Late worker error.");

        var result = staging.findStagingRecord(stagingId).orElseThrow();
        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(result.measurementId()).isEqualTo(accepted.measurementId());
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void mapsDeferredCommitFailureAndRollsBackEveryAcceptanceWrite() {
        UUID stagingId = receiveRawPayload();
        jdbc.execute("CREATE TABLE factorybridge.test_allowed_measurement (id uuid PRIMARY KEY)");
        jdbc.execute(
                """
                ALTER TABLE factorybridge.delivery_outbox ADD CONSTRAINT test_deferred_outbox
                FOREIGN KEY (measurement_id) REFERENCES factorybridge.test_allowed_measurement(id)
                DEFERRABLE INITIALLY DEFERRED
                """);
        try {
            // SQL statements 全部成功，直到 commit 檢查 deferred FK 才失敗。
            assertThatThrownBy(
                            () ->
                                    measurements.acceptMeasurement(
                                            stagingId,
                                            measurement("commit-failure", QualityStatus.GOOD),
                                            HASH,
                                            CORRELATION_ID,
                                            NOW))
                    .isInstanceOfSatisfying(
                            FactoryBridgeException.class,
                            exception -> {
                                assertThat(exception.errorCode())
                                        .isEqualTo(ErrorCode.INFRASTRUCTURE_UNAVAILABLE);
                                assertThat(exception.getCause()).isNotNull();
                            });
            assertThat(canonicalCount()).isZero();
            assertThat(outboxCount()).isZero();
            assertThat(staging.findStagingRecord(stagingId).orElseThrow().status())
                    .isEqualTo("RECEIVED");
        } finally {
            jdbc.execute(
                    "ALTER TABLE factorybridge.delivery_outbox DROP CONSTRAINT test_deferred_outbox");
            jdbc.execute("DROP TABLE factorybridge.test_allowed_measurement");
        }
    }

    @Test
    void retainsWarningAndBadMeasurementsInWarehouseWithoutSendingThemDownstream() {
        for (QualityStatus quality : List.of(QualityStatus.WARNING, QualityStatus.BAD)) {
            Acceptance accepted = accept("quality-" + quality, quality);
            assertThat(deliveries.listDeliveries(accepted.measurementId()))
                    .extracting(delivery -> delivery.destination())
                    .containsExactly(Destination.DATA_WAREHOUSE);
        }
        assertThat(canonicalCount()).isEqualTo(2);
        assertThat(outboxCount()).isEqualTo(2);
    }

    @Test
    void listsMeasurementsInStableOrderWhenCreationTimesAreEqual() {
        accept("first", QualityStatus.GOOD);
        accept("second", QualityStatus.GOOD);
        accept("third", QualityStatus.GOOD);
        var firstRead = measurements.listMeasurements(2);
        assertThat(firstRead).hasSize(2).isEqualTo(measurements.listMeasurements(2));
        assertThat(measurements.listMeasurements(3))
                .startsWith(
                        firstRead.toArray(
                                new io.factorybridge.application.model.StoredMeasurement[0]));
    }

    @Test
    void databaseRejectsNonFiniteValuesAndMismatchedCanonicalUnits() {
        UUID id = accept("database-guards", QualityStatus.GOOD).measurementId();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE factorybridge.measurement SET numeric_value = 'NaN'::numeric WHERE id = ?",
                                        id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE factorybridge.measurement SET standard_unit = 'F' WHERE id = ?",
                                        id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(measurements.findMeasurement(id).orElseThrow().measurement().standardUnit())
                .isEqualTo("C");
    }
}
