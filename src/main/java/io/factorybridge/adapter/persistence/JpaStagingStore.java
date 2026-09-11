package io.factorybridge.adapter.persistence;

import io.factorybridge.application.model.SourceRecordIdentity;
import io.factorybridge.application.model.StagingRecord;
import io.factorybridge.application.port.StagingStore;
import io.factorybridge.domain.ErrorCode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class JpaStagingStore implements StagingStore {
    private final StagingJpaRepository repository;
    private final PersistenceTransactions transactions;

    public JpaStagingStore(StagingJpaRepository repository, PersistenceTransactions transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    @Override
    public UUID recordReceived(
            String rawPayload,
            String correlationId,
            Instant receivedAt,
            SourceRecordIdentity expectedSource) {
        return transactions.writeIndependently(
                () -> {
                    UUID stagingId = UUID.randomUUID();
                    repository.saveAndFlush(
                            new StagingEntity(
                                    stagingId,
                                    rawPayload,
                                    correlationId,
                                    receivedAt,
                                    expectedSource));
                    return stagingId;
                });
    }

    @Override
    public void recordRejection(UUID stagingId, ErrorCode errorCode, String message) {
        transactions.writeIndependently(
                () ->
                        repository.rejectReceivedRecord(
                                stagingId, errorCode.name(), truncateErrorMessage(message)));
    }

    @Override
    public Optional<StagingRecord> findStagingRecord(UUID stagingId) {
        return transactions.read(() -> repository.findById(stagingId).map(StagingEntity::toRecord));
    }

    private String truncateErrorMessage(String message) {
        return message == null ? null : message.substring(0, Math.min(message.length(), 512));
    }
}
