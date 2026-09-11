package io.factorybridge.application;

import static io.factorybridge.application.ApplicationTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.factorybridge.application.model.Delivery;
import io.factorybridge.application.model.Destination;
import io.factorybridge.application.port.DeliveryStore;
import io.factorybridge.application.port.DownstreamClient;
import io.factorybridge.application.port.MeasurementStore;
import io.factorybridge.application.port.WarehouseWriter;
import io.factorybridge.domain.ErrorCode;
import io.factorybridge.domain.FactoryBridgeException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeliveryDispatcherTest {
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    @Mock private DeliveryStore deliveryStore;
    @Mock private MeasurementStore measurementStore;
    @Mock private DownstreamClient downstreamClient;
    @Mock private WarehouseWriter warehouseWriter;
    private DeliveryDispatcher dispatcher;

    @BeforeEach
    void createDispatcher() {
        DeliveryRetryPolicy policy =
                new DeliveryRetryPolicy(
                        3, Duration.ofSeconds(2), Duration.ofSeconds(30), () -> 0.0);
        dispatcher =
                new DeliveryDispatcher(
                        deliveryStore,
                        measurementStore,
                        downstreamClient,
                        warehouseWriter,
                        policy,
                        CLOCK,
                        LEASE_DURATION);
    }

    @Test
    void routesDownstreamDeliveryWithItsStableIdempotencyKeyAndLeaseToken() {
        prepareDelivery(Destination.DOWNSTREAM, 1);
        when(deliveryStore.markDelivered(DELIVERY_ID, LEASE_TOKEN, NOW)).thenReturn(true);

        assertEquals(1, dispatcher.dispatchDueDeliveries(1));

        InOrder order = inOrder(deliveryStore, measurementStore, downstreamClient);
        order.verify(deliveryStore).claimDueDeliveries(NOW, LEASE_DURATION, 1);
        order.verify(measurementStore).findMeasurement(MEASUREMENT_ID);
        order.verify(downstreamClient)
                .deliverMeasurement(
                        DELIVERY_ID, MEASUREMENT_ID, canonicalMeasurement(), CORRELATION_ID);
        order.verify(deliveryStore).markDelivered(DELIVERY_ID, LEASE_TOKEN, NOW);
        verifyNoInteractions(warehouseWriter);
        verify(deliveryStore, never()).markFailed(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void routesWarehouseDeliveryWithoutCallingTheDownstreamSystem() {
        prepareDelivery(Destination.DATA_WAREHOUSE, 1);

        assertEquals(1, dispatcher.dispatchDueDeliveries(1));

        verify(warehouseWriter).writeMeasurement(MEASUREMENT_ID, canonicalMeasurement());
        verify(deliveryStore).markDelivered(DELIVERY_ID, LEASE_TOKEN, NOW);
        verifyNoInteractions(downstreamClient);
    }

    @ParameterizedTest
    @CsvSource({"1,false,2", "2,false,4", "3,true,8"})
    void persistsRetryOrDeadStateUsingTheClaimedAttemptCount(
            int attempt, boolean dead, long delaySeconds) {
        prepareDelivery(Destination.DOWNSTREAM, attempt);
        FactoryBridgeException outage =
                new FactoryBridgeException(
                        ErrorCode.DOWNSTREAM_UNAVAILABLE, "Downstream request timed out.");
        doThrow(outage)
                .when(downstreamClient)
                .deliverMeasurement(
                        DELIVERY_ID, MEASUREMENT_ID, canonicalMeasurement(), CORRELATION_ID);

        assertEquals(1, dispatcher.dispatchDueDeliveries(1));

        verify(deliveryStore)
                .markFailed(
                        DELIVERY_ID,
                        LEASE_TOKEN,
                        ErrorCode.DOWNSTREAM_UNAVAILABLE,
                        outage.getMessage(),
                        NOW.plusSeconds(delaySeconds),
                        dead);
        verify(deliveryStore, never()).markDelivered(any(), any(), any());
    }

    @Test
    void sendsPermanentDownstreamRejectionDirectlyToDeadState() {
        prepareDelivery(Destination.DOWNSTREAM, 1);
        FactoryBridgeException rejection =
                new FactoryBridgeException(
                        ErrorCode.DOWNSTREAM_REJECTED, "Downstream returned HTTP 422.");
        doThrow(rejection).when(downstreamClient).deliverMeasurement(any(), any(), any(), any());

        dispatcher.dispatchDueDeliveries(1);

        verify(deliveryStore)
                .markFailed(
                        DELIVERY_ID,
                        LEASE_TOKEN,
                        ErrorCode.DOWNSTREAM_REJECTED,
                        rejection.getMessage(),
                        NOW.plusSeconds(2),
                        true);
        verify(deliveryStore, never()).markDelivered(any(), any(), any());
    }

    @Test
    void retriesWarehouseWriteFailureWithoutDeliveringToTheOtherDestination() {
        prepareDelivery(Destination.DATA_WAREHOUSE, 1);
        FactoryBridgeException failure =
                new FactoryBridgeException(
                        ErrorCode.DATA_WAREHOUSE_WRITE_FAILED, "Warehouse connection failed.");
        doThrow(failure)
                .when(warehouseWriter)
                .writeMeasurement(MEASUREMENT_ID, canonicalMeasurement());

        dispatcher.dispatchDueDeliveries(1);

        verify(deliveryStore)
                .markFailed(
                        DELIVERY_ID,
                        LEASE_TOKEN,
                        ErrorCode.DATA_WAREHOUSE_WRITE_FAILED,
                        failure.getMessage(),
                        NOW.plusSeconds(2),
                        false);
        verifyNoInteractions(downstreamClient);
    }

    @Test
    void retriesOperationalReadFailureBeforeAttemptingAnExternalSideEffect() {
        prepareClaim(claimedDelivery(Destination.DOWNSTREAM, 1));
        FactoryBridgeException failure =
                new FactoryBridgeException(
                        ErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                        "Measurement database connection failed.");
        when(measurementStore.findMeasurement(MEASUREMENT_ID)).thenThrow(failure);

        dispatcher.dispatchDueDeliveries(1);

        verify(deliveryStore)
                .markFailed(
                        DELIVERY_ID,
                        LEASE_TOKEN,
                        ErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                        failure.getMessage(),
                        NOW.plusSeconds(2),
                        false);
        verifyNoInteractions(downstreamClient, warehouseWriter);
    }

    @Test
    void deadLettersAnOutboxRecordWhoseCanonicalMeasurementCannotBeFound() {
        prepareClaim(claimedDelivery(Destination.DOWNSTREAM, 1));
        when(measurementStore.findMeasurement(MEASUREMENT_ID)).thenReturn(Optional.empty());

        dispatcher.dispatchDueDeliveries(1);

        verify(deliveryStore)
                .markFailed(
                        eq(DELIVERY_ID),
                        eq(LEASE_TOKEN),
                        eq(ErrorCode.RECORD_NOT_FOUND),
                        anyString(),
                        eq(NOW.plusSeconds(2)),
                        eq(true));
        verifyNoInteractions(downstreamClient, warehouseWriter);
    }

    @Test
    void stopsPoisonDeliveryAfterAnUnexpectedRuntimeFailure() {
        prepareDelivery(Destination.DOWNSTREAM, 1);
        doThrow(new IllegalStateException("Transformer invariant violated."))
                .when(downstreamClient)
                .deliverMeasurement(any(), any(), any(), any());

        assertEquals(1, dispatcher.dispatchDueDeliveries(1));

        verify(deliveryStore)
                .markFailed(
                        eq(DELIVERY_ID),
                        eq(LEASE_TOKEN),
                        eq(ErrorCode.INTERNAL_ERROR),
                        anyString(),
                        any(),
                        eq(true));
        verify(deliveryStore, never()).markDelivered(any(), any(), any());
    }

    @Test
    void leavesSuccessfulDeliveryToLeaseRecoveryWhenAcknowledgementCannotBeStored() {
        prepareDelivery(Destination.DOWNSTREAM, 1);
        FactoryBridgeException acknowledgementFailure =
                new FactoryBridgeException(
                        ErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                        "Delivery acknowledgement could not be committed.");
        when(deliveryStore.markDelivered(DELIVERY_ID, LEASE_TOKEN, NOW))
                .thenThrow(acknowledgementFailure);

        assertSame(
                acknowledgementFailure,
                assertThrows(
                        FactoryBridgeException.class, () -> dispatcher.dispatchDueDeliveries(1)));

        verify(downstreamClient)
                .deliverMeasurement(
                        DELIVERY_ID, MEASUREMENT_ID, canonicalMeasurement(), CORRELATION_ID);
        // 下游已接受資料，ack 失敗不能改寫成「下游失敗」，更不能產生新的 idempotency key。
        verify(deliveryStore, never()).markFailed(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void doesNotOverwriteANewerWorkersStateWhenTheAcknowledgementLeaseIsStale() {
        prepareDelivery(Destination.DOWNSTREAM, 1);
        when(deliveryStore.markDelivered(DELIVERY_ID, LEASE_TOKEN, NOW)).thenReturn(false);

        dispatcher.dispatchDueDeliveries(1);

        verify(deliveryStore).markDelivered(DELIVERY_ID, LEASE_TOKEN, NOW);
        verify(deliveryStore, never()).markFailed(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void doesNotRetryAnUnfencedWriteWhenTheFailureAcknowledgementLeaseIsStale() {
        prepareDelivery(Destination.DOWNSTREAM, 1);
        FactoryBridgeException failure =
                new FactoryBridgeException(
                        ErrorCode.DOWNSTREAM_REJECTED, "Downstream rejected the request.");
        doThrow(failure).when(downstreamClient).deliverMeasurement(any(), any(), any(), any());
        when(deliveryStore.markFailed(
                        DELIVERY_ID,
                        LEASE_TOKEN,
                        ErrorCode.DOWNSTREAM_REJECTED,
                        failure.getMessage(),
                        NOW.plusSeconds(2),
                        true))
                .thenReturn(false);

        dispatcher.dispatchDueDeliveries(1);

        // 舊 worker 只能提交附帶原 lease 的一次回報，不得再用不帶 fencing 的更新強行覆寫。
        verify(deliveryStore)
                .markFailed(
                        DELIVERY_ID,
                        LEASE_TOKEN,
                        ErrorCode.DOWNSTREAM_REJECTED,
                        failure.getMessage(),
                        NOW.plusSeconds(2),
                        true);
        verify(deliveryStore).claimDueDeliveries(NOW, LEASE_DURATION, 1);
        verifyNoMoreInteractions(deliveryStore);
    }

    @Test
    void propagatesFailureToRecordARetrySoTheOriginalLeaseCanBeRecovered() {
        prepareDelivery(Destination.DOWNSTREAM, 1);
        FactoryBridgeException unavailable =
                new FactoryBridgeException(
                        ErrorCode.DOWNSTREAM_UNAVAILABLE, "Downstream timed out.");
        doThrow(unavailable).when(downstreamClient).deliverMeasurement(any(), any(), any(), any());
        FactoryBridgeException writeFailure =
                new FactoryBridgeException(
                        ErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                        "Retry state could not be committed.");
        when(deliveryStore.markFailed(any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(writeFailure);

        assertSame(
                writeFailure,
                assertThrows(
                        FactoryBridgeException.class, () -> dispatcher.dispatchDueDeliveries(1)));
        verify(deliveryStore, never()).markDelivered(any(), any(), any());
    }

    @Test
    void claimsOneRecordAtATimeAndStopsWhenNoWorkRemains() {
        Delivery first = claimedDelivery(Destination.DOWNSTREAM, 1);
        when(deliveryStore.claimDueDeliveries(NOW, LEASE_DURATION, 1))
                .thenReturn(List.of(first), List.of());
        when(measurementStore.findMeasurement(MEASUREMENT_ID))
                .thenReturn(Optional.of(storedMeasurement()));

        assertEquals(1, dispatcher.dispatchDueDeliveries(10));

        verify(deliveryStore, times(2)).claimDueDeliveries(NOW, LEASE_DURATION, 1);
        verify(downstreamClient, times(1)).deliverMeasurement(any(), any(), any(), any());
    }

    @Test
    void returnsZeroWithoutLoadingMeasurementsWhenTheQueueIsEmpty() {
        when(deliveryStore.claimDueDeliveries(NOW, LEASE_DURATION, 1)).thenReturn(List.of());

        assertEquals(0, dispatcher.dispatchDueDeliveries(10));

        verifyNoInteractions(measurementStore, downstreamClient, warehouseWriter);
    }

    @Test
    void replaysADeadDeliveryWithoutChangingItsIdempotencyKey() {
        Delivery dead =
                new Delivery(
                        DELIVERY_ID,
                        MEASUREMENT_ID,
                        Destination.DOWNSTREAM,
                        "DEAD",
                        3,
                        3,
                        0,
                        NOW,
                        null,
                        CORRELATION_ID,
                        ErrorCode.DOWNSTREAM_UNAVAILABLE.name(),
                        "Timeout.");
        Delivery replayed =
                new Delivery(
                        DELIVERY_ID,
                        MEASUREMENT_ID,
                        Destination.DOWNSTREAM,
                        "PENDING",
                        0,
                        3,
                        1,
                        NOW,
                        null,
                        CORRELATION_ID,
                        ErrorCode.DOWNSTREAM_UNAVAILABLE.name(),
                        "Timeout.");
        when(deliveryStore.findDelivery(DELIVERY_ID))
                .thenReturn(Optional.of(dead), Optional.of(replayed));
        when(deliveryStore.replayDeadDelivery(DELIVERY_ID, NOW)).thenReturn(true);

        Delivery result = dispatcher.replayDeadDelivery(DELIVERY_ID);

        assertEquals(replayed, result);
        assertEquals(dead.id(), result.id());
        assertEquals(0, result.attemptCount());
        assertEquals(3, result.totalAttempts());
        assertEquals(1, result.replayCount());
        verifyNoInteractions(measurementStore, downstreamClient, warehouseWriter);
    }

    @Test
    void rejectsManualReplayWhenTheStoredDeliveryIsNotDead() {
        when(deliveryStore.findDelivery(DELIVERY_ID))
                .thenReturn(Optional.of(claimedDelivery(Destination.DOWNSTREAM, 1)));
        when(deliveryStore.replayDeadDelivery(DELIVERY_ID, NOW)).thenReturn(false);

        FactoryBridgeException failure =
                assertThrows(
                        FactoryBridgeException.class,
                        () -> dispatcher.replayDeadDelivery(DELIVERY_ID));

        assertEquals(ErrorCode.DELIVERY_NOT_REPLAYABLE, failure.errorCode());
        verifyNoInteractions(measurementStore, downstreamClient, warehouseWriter);
    }

    @Test
    void reportsMissingDeliveryBeforeAttemptingManualReplay() {
        when(deliveryStore.findDelivery(DELIVERY_ID)).thenReturn(Optional.empty());

        FactoryBridgeException failure =
                assertThrows(
                        FactoryBridgeException.class,
                        () -> dispatcher.replayDeadDelivery(DELIVERY_ID));

        assertEquals(ErrorCode.RECORD_NOT_FOUND, failure.errorCode());
        verify(deliveryStore, never()).replayDeadDelivery(any(UUID.class), any());
    }

    private void prepareDelivery(Destination destination, int attempt) {
        prepareClaim(claimedDelivery(destination, attempt));
        when(measurementStore.findMeasurement(MEASUREMENT_ID))
                .thenReturn(Optional.of(storedMeasurement()));
    }

    private void prepareClaim(Delivery delivery) {
        when(deliveryStore.claimDueDeliveries(NOW, LEASE_DURATION, 1))
                .thenReturn(List.of(delivery));
    }
}
