package io.factorybridge.application;

import io.factorybridge.application.model.*;
import io.factorybridge.application.port.*;
import io.factorybridge.domain.*;
import java.time.*;
import java.util.UUID;

public final class DeliveryDispatcher {
    private static final System.Logger log = System.getLogger(DeliveryDispatcher.class.getName());
    private final DeliveryStore deliveryStore;
    private final MeasurementStore measurementStore;
    private final DownstreamClient downstreamClient;
    private final WarehouseWriter warehouseWriter;
    private final DeliveryRetryPolicy retryPolicy;
    private final Clock clock;
    private final Duration leaseDuration;

    public DeliveryDispatcher(
            DeliveryStore deliveryStore,
            MeasurementStore measurementStore,
            DownstreamClient downstreamClient,
            WarehouseWriter warehouseWriter,
            DeliveryRetryPolicy retryPolicy,
            Clock clock,
            Duration leaseDuration) {
        this.deliveryStore = deliveryStore;
        this.measurementStore = measurementStore;
        this.downstreamClient = downstreamClient;
        this.warehouseWriter = warehouseWriter;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
    }

    public int dispatchDueDeliveries(int limit) {
        // 一次只認領一筆，避免批次末端的 lease 在前面 HTTP 等待期間過期。
        int dispatched = 0;
        for (int index = 0; index < limit; index++) {
            var claimed = deliveryStore.claimDueDeliveries(clock.instant(), leaseDuration, 1);
            if (claimed.isEmpty()) break;
            deliverClaimedMeasurement(claimed.getFirst());
            dispatched++;
        }
        return dispatched;
    }

    private void deliverClaimedMeasurement(Delivery delivery) {
        try {
            var stored =
                    measurementStore
                            .findMeasurement(delivery.measurementId())
                            .orElseThrow(
                                    () ->
                                            new FactoryBridgeException(
                                                    ErrorCode.RECORD_NOT_FOUND,
                                                    "Delivery measurement was not found."));
            switch (delivery.destination()) {
                case DOWNSTREAM ->
                        downstreamClient.deliverMeasurement(
                                delivery.id(),
                                stored.id(),
                                stored.measurement(),
                                delivery.correlationId());
                case DATA_WAREHOUSE ->
                        warehouseWriter.writeMeasurement(stored.id(), stored.measurement());
            }
        } catch (FactoryBridgeException failure) {
            recordDeliveryFailure(delivery, failure);
            return;
        } catch (RuntimeException unexpectedFailure) {
            // 一筆有程式或契約缺陷的工作應隔離，不能無限搶回同一 lease 阻塞其餘投遞。
            log.log(
                    System.Logger.Level.ERROR,
                    "delivery_unexpected_failure deliveryId="
                            + delivery.id()
                            + " correlationId="
                            + delivery.correlationId(),
                    unexpectedFailure);
            recordDeliveryFailure(
                    delivery,
                    new FactoryBridgeException(
                            ErrorCode.INTERNAL_ERROR,
                            ErrorCode.INTERNAL_ERROR.defaultMessage(),
                            unexpectedFailure));
            return;
        }
        // 送出成功但 acknowledgement DB 失敗時，不把它當成下游失敗；等待 lease 回收後以相同 key 重送。
        boolean recorded =
                deliveryStore.markDelivered(delivery.id(), delivery.leaseToken(), clock.instant());
        log.log(
                recorded ? System.Logger.Level.INFO : System.Logger.Level.WARNING,
                "delivery_acknowledged deliveryId={0} measurementId={1} destination={2} correlationId={3} leaseAccepted={4}",
                delivery.id(),
                delivery.measurementId(),
                delivery.destination(),
                delivery.correlationId(),
                recorded);
    }

    private void recordDeliveryFailure(Delivery delivery, FactoryBridgeException failure) {
        boolean dead = retryPolicy.shouldStopRetrying(delivery.attemptCount(), failure.errorCode());
        boolean recorded =
                deliveryStore.markFailed(
                        delivery.id(),
                        delivery.leaseToken(),
                        failure.errorCode(),
                        failure.getMessage(),
                        clock.instant()
                                .plus(retryPolicy.delayAfterAttempt(delivery.attemptCount())),
                        dead);
        // 只有目前 lease 的回報能套用；stale worker 的預期狀態不能被 log 描述成已提交的狀態。
        log.log(
                System.Logger.Level.WARNING,
                "delivery_failure_acknowledged deliveryId={0} destination={1} correlationId={2} attempt={3} "
                        + "errorCode={4} requestedStatus={5} leaseAccepted={6}",
                delivery.id(),
                delivery.destination(),
                delivery.correlationId(),
                delivery.attemptCount(),
                failure.errorCode(),
                dead ? "DEAD" : "RETRY",
                recorded);
    }

    public Delivery replayDeadDelivery(UUID deliveryId) {
        var existing =
                deliveryStore
                        .findDelivery(deliveryId)
                        .orElseThrow(
                                () ->
                                        new FactoryBridgeException(
                                                ErrorCode.RECORD_NOT_FOUND,
                                                "Delivery was not found."));
        if (!deliveryStore.replayDeadDelivery(existing.id(), clock.instant())) {
            throw new FactoryBridgeException(
                    ErrorCode.DELIVERY_NOT_REPLAYABLE, "Only DEAD deliveries can be replayed.");
        }
        return deliveryStore.findDelivery(deliveryId).orElseThrow();
    }
}
