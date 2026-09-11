package io.factorybridge.application.port;

import io.factorybridge.application.model.Delivery;
import io.factorybridge.domain.ErrorCode;
import java.time.*;
import java.util.*;

public interface DeliveryStore {
    // claim 必須原子化且支援多 worker；過期 lease 可重新認領，舊 worker 不可覆寫新狀態。
    List<Delivery> claimDueDeliveries(Instant now, Duration leaseDuration, int limit);

    boolean markDelivered(UUID deliveryId, UUID leaseToken, Instant deliveredAt);

    boolean markFailed(
            UUID deliveryId,
            UUID leaseToken,
            ErrorCode errorCode,
            String message,
            Instant nextAttemptAt,
            boolean dead);

    Optional<Delivery> findDelivery(UUID deliveryId);

    List<Delivery> listDeliveries(UUID measurementId);

    boolean replayDeadDelivery(UUID deliveryId, Instant now);
}
