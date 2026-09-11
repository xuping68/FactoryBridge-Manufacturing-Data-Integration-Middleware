package io.factorybridge.adapter.persistence;

import io.factorybridge.application.model.SourceRecordIdentity;
import io.factorybridge.application.model.StagingRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** 原始收件紀錄有獨立生命週期，不能隨 canonical 交易失敗而消失。 */
@Entity
@Table(name = "staging_record", schema = "factorybridge")
class StagingEntity {
    @Id private UUID id;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "bytea")
    private byte[] rawPayload;

    @Column(name = "correlation_id", nullable = false, length = 128)
    private String correlationId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "measurement_id")
    private UUID measurementId;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "expected_source", length = 128)
    private String expectedSource;

    @Column(name = "expected_source_record_id", length = 128)
    private String expectedSourceRecordId;

    protected StagingEntity() {}

    StagingEntity(
            UUID id,
            String rawPayload,
            String correlationId,
            Instant receivedAt,
            SourceRecordIdentity expectedIdentity) {
        this.id = id;
        // HTTP 邊界已確認 UTF-8；以 bytes 保存可涵蓋含 NUL 的無效 JSON。
        this.rawPayload = rawPayload.getBytes(StandardCharsets.UTF_8);
        this.correlationId = correlationId;
        this.receivedAt = receivedAt;
        this.status = "RECEIVED";
        if (expectedIdentity != null) {
            this.expectedSource = expectedIdentity.sourceSystem();
            this.expectedSourceRecordId = expectedIdentity.sourceRecordId();
        }
    }

    void recordAcceptance(UUID acceptedMeasurementId, boolean duplicate) {
        if (!"RECEIVED".equals(status)) {
            throw new IllegalStateException("Only a received staging record can be accepted.");
        }
        status = duplicate ? "DUPLICATE" : "ACCEPTED";
        measurementId = acceptedMeasurementId;
        errorCode = null;
        errorMessage = null;
    }

    StagingRecord toRecord() {
        SourceRecordIdentity expectedIdentity =
                expectedSource == null
                        ? null
                        : new SourceRecordIdentity(expectedSource, expectedSourceRecordId);
        return new StagingRecord(
                id,
                new String(rawPayload, StandardCharsets.UTF_8),
                correlationId,
                receivedAt,
                status,
                measurementId,
                errorCode,
                errorMessage,
                expectedIdentity);
    }
}
