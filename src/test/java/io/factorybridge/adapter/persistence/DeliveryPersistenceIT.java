package io.factorybridge.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.factorybridge.application.model.Delivery;
import io.factorybridge.domain.ErrorCode;
import io.factorybridge.domain.QualityStatus;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class DeliveryPersistenceIT extends PostgresIntegrationSupport {
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

    @Test
    void concurrentWorkersClaimDisjointDeliveriesWithoutWaitingForEachOther() throws Exception {
        IntStream.range(0, 12).forEach(index -> accept("claim-" + index, QualityStatus.WARNING));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first =
                    executor.submit(
                            () -> {
                                start.await();
                                return deliveries.claimDueDeliveries(NOW, LEASE_DURATION, 6);
                            });
            var second =
                    executor.submit(
                            () -> {
                                start.await();
                                return deliveries.claimDueDeliveries(NOW, LEASE_DURATION, 6);
                            });
            start.countDown();
            List<Delivery> firstClaims = first.get(10, TimeUnit.SECONDS);
            List<Delivery> secondClaims = second.get(10, TimeUnit.SECONDS);
            assertThat(firstClaims).hasSize(6).allMatch(delivery -> delivery.attemptCount() == 1);
            assertThat(secondClaims).hasSize(6).allMatch(delivery -> delivery.totalAttempts() == 1);
            assertThat(firstClaims)
                    .extracting(Delivery::id)
                    .doesNotContainAnyElementsOf(secondClaims.stream().map(Delivery::id).toList());
            assertThat(deliveries.claimDueDeliveries(NOW, LEASE_DURATION, 12)).isEmpty();
        }
    }

    @Test
    void staleWorkerCannotCompleteOrFailDeliveryAfterItsLeaseHasBeenReclaimed() {
        accept("fencing", QualityStatus.WARNING);
        Delivery stale = deliveries.claimDueDeliveries(NOW, LEASE_DURATION, 1).getFirst();
        Delivery current =
                deliveries
                        .claimDueDeliveries(NOW.plus(LEASE_DURATION), LEASE_DURATION, 1)
                        .getFirst();

        assertThat(current.id()).isEqualTo(stale.id());
        assertThat(current.leaseToken()).isNotEqualTo(stale.leaseToken());
        assertThat(current.attemptCount()).isEqualTo(2);
        assertThat(deliveries.markDelivered(stale.id(), stale.leaseToken(), NOW)).isFalse();
        assertThat(
                        deliveries.markFailed(
                                stale.id(),
                                stale.leaseToken(),
                                ErrorCode.DOWNSTREAM_UNAVAILABLE,
                                "Old worker failure.",
                                NOW,
                                true))
                .isFalse();
        assertThat(deliveries.findDelivery(current.id()).orElseThrow().status())
                .isEqualTo("IN_FLIGHT");
        assertThat(
                        deliveries.markDelivered(
                                current.id(), current.leaseToken(), NOW.plusSeconds(31)))
                .isTrue();
        assertThat(deliveries.findDelivery(current.id()).orElseThrow().status())
                .isEqualTo("DELIVERED");
    }

    @Test
    void skipsRowLockedByAnotherTransactionAndClaimsItAfterTheLockIsReleased() {
        UUID firstMeasurement = accept("locked-row", QualityStatus.WARNING).measurementId();
        UUID secondMeasurement = accept("available-row", QualityStatus.WARNING).measurementId();
        UUID lockedDelivery = deliveries.listDeliveries(firstMeasurement).getFirst().id();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            transaction -> {
                                jdbc.queryForObject(
                                        "SELECT id FROM factorybridge.delivery_outbox WHERE id = ? FOR UPDATE",
                                        UUID.class,
                                        lockedDelivery);
                                var otherWorker =
                                        executor.submit(
                                                () ->
                                                        deliveries.claimDueDeliveries(
                                                                NOW, LEASE_DURATION, 2));
                                try {
                                    // 若 SQL 忘記 SKIP LOCKED，這裡會逾時，無法靠快速先後完成交易矇混通過。
                                    assertThat(otherWorker.get(2, TimeUnit.SECONDS))
                                            .extracting(Delivery::measurementId)
                                            .containsExactly(secondMeasurement);
                                } catch (Exception exception) {
                                    throw new AssertionError(
                                            "Another worker should skip the locked delivery.",
                                            exception);
                                }
                            });
        }
        assertThat(deliveries.claimDueDeliveries(NOW, LEASE_DURATION, 2))
                .extracting(Delivery::id)
                .containsExactly(lockedDelivery);
    }

    @Test
    void retriesOnlyWhenDueAndIncrementsBothAttemptCounters() {
        accept("future-retry", QualityStatus.WARNING);
        Delivery firstAttempt = deliveries.claimDueDeliveries(NOW, LEASE_DURATION, 1).getFirst();
        assertThat(
                        deliveries.markFailed(
                                firstAttempt.id(),
                                firstAttempt.leaseToken(),
                                ErrorCode.DATA_WAREHOUSE_WRITE_FAILED,
                                "Temporary failure.",
                                NOW.plusSeconds(5),
                                false))
                .isTrue();

        assertThat(deliveries.claimDueDeliveries(NOW.plusSeconds(4), LEASE_DURATION, 1)).isEmpty();
        Delivery secondAttempt =
                deliveries.claimDueDeliveries(NOW.plusSeconds(5), LEASE_DURATION, 1).getFirst();
        assertThat(secondAttempt.attemptCount()).isEqualTo(2);
        assertThat(secondAttempt.totalAttempts()).isEqualTo(2);
        assertThat(secondAttempt.lastErrorCode()).isEqualTo("DATA_WAREHOUSE_WRITE_FAILED");
    }

    @Test
    void manualReplayResetsOnlyDeadDeliveryRetryBudgetAndPreservesAuditCounters() {
        accept("manual-replay", QualityStatus.WARNING);
        Delivery firstAttempt = deliveries.claimDueDeliveries(NOW, LEASE_DURATION, 1).getFirst();
        assertThat(deliveries.replayDeadDelivery(firstAttempt.id(), NOW)).isFalse();
        deliveries.markFailed(
                firstAttempt.id(),
                firstAttempt.leaseToken(),
                ErrorCode.DATA_WAREHOUSE_WRITE_FAILED,
                "Retry budget exhausted.",
                NOW,
                true);

        assertThat(deliveries.claimDueDeliveries(NOW.plusSeconds(60), LEASE_DURATION, 1)).isEmpty();
        assertThat(deliveries.replayDeadDelivery(firstAttempt.id(), NOW.plusSeconds(60))).isTrue();
        assertThat(deliveries.replayDeadDelivery(firstAttempt.id(), NOW.plusSeconds(60))).isFalse();
        Delivery replayed = deliveries.findDelivery(firstAttempt.id()).orElseThrow();
        assertThat(replayed.status()).isEqualTo("PENDING");
        assertThat(replayed.attemptCount()).isZero();
        assertThat(replayed.totalAttempts()).isEqualTo(1);
        assertThat(replayed.replayCount()).isEqualTo(1);
        assertThat(replayed.lastErrorMessage()).isEqualTo("Retry budget exhausted.");

        Delivery nextAttempt =
                deliveries.claimDueDeliveries(NOW.plusSeconds(60), LEASE_DURATION, 1).getFirst();
        assertThat(nextAttempt.attemptCount()).isEqualTo(1);
        assertThat(nextAttempt.totalAttempts()).isEqualTo(2);
        deliveries.markDelivered(nextAttempt.id(), nextAttempt.leaseToken(), NOW.plusSeconds(61));
        assertThat(deliveries.replayDeadDelivery(nextAttempt.id(), NOW.plusSeconds(62))).isFalse();
    }

    @Test
    void unknownDeliveryCannotBeMarkedOrReplayed() {
        UUID missing = UUID.randomUUID();
        assertThat(deliveries.findDelivery(missing)).isEmpty();
        assertThat(deliveries.markDelivered(missing, UUID.randomUUID(), NOW)).isFalse();
        assertThat(deliveries.replayDeadDelivery(missing, NOW)).isFalse();
    }
}
