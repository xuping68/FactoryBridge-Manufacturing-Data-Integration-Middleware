package io.factorybridge.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.factorybridge.domain.ErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class DeliveryRetryPolicyTest {
    private final DeliveryRetryPolicy retryPolicy =
            new DeliveryRetryPolicy(3, Duration.ofSeconds(2), Duration.ofSeconds(30), () -> 0.0);

    @ParameterizedTest
    @EnumSource(
            value = ErrorCode.class,
            names = {
                "DOWNSTREAM_UNAVAILABLE",
                "DATA_WAREHOUSE_WRITE_FAILED",
                "INFRASTRUCTURE_UNAVAILABLE"
            })
    void retriesTransientFailuresOnlyWithinTheAttemptBudget(ErrorCode errorCode) {
        assertFalse(retryPolicy.shouldStopRetrying(1, errorCode));
        assertFalse(retryPolicy.shouldStopRetrying(2, errorCode));
        assertTrue(retryPolicy.shouldStopRetrying(3, errorCode));
        assertTrue(retryPolicy.shouldStopRetrying(4, errorCode));
    }

    @ParameterizedTest
    @EnumSource(
            value = ErrorCode.class,
            names = {
                "DOWNSTREAM_REJECTED",
                "INVALID_MEASUREMENT_VALUE",
                "RECORD_NOT_FOUND",
                "INTERNAL_ERROR"
            })
    void sendsPermanentFailuresDirectlyToTheDeadLetterState(ErrorCode errorCode) {
        assertTrue(retryPolicy.shouldStopRetrying(1, errorCode));
    }

    @ParameterizedTest
    @CsvSource({"1,2000", "2,4000", "3,8000", "4,16000", "5,30000", "30,30000"})
    void increasesDelayExponentiallyWithoutExceedingTheConfiguredMaximum(
            int attemptCount, long milliseconds) {
        assertEquals(Duration.ofMillis(milliseconds), retryPolicy.delayAfterAttempt(attemptCount));
    }

    @Test
    void addsReproducibleJitterWithoutExceedingTheMaximumDelay() {
        DeliveryRetryPolicy jittered =
                new DeliveryRetryPolicy(
                        5, Duration.ofSeconds(2), Duration.ofSeconds(30), () -> 0.5);

        assertEquals(Duration.ofMillis(2200), jittered.delayAfterAttempt(1));
        assertEquals(Duration.ofSeconds(30), jittered.delayAfterAttempt(5));
    }

    @ParameterizedTest
    @CsvSource({"0,2,30", "1,0,30", "1,-1,30", "1,30,2"})
    void rejectsInvalidRetryConfigurationAtConstruction(
            int maxAttempts, int initialSeconds, int maximumSeconds) {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DeliveryRetryPolicy(
                                maxAttempts,
                                Duration.ofSeconds(initialSeconds),
                                Duration.ofSeconds(maximumSeconds),
                                () -> 0.0));
    }

    @Test
    void rejectsSubMillisecondDelaysInsteadOfCreatingABusyRetryLoop() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DeliveryRetryPolicy(
                                3, Duration.ofNanos(999_999), Duration.ofSeconds(30), () -> 0.0));
    }

    @Test
    void rejectsUnboundedDurationsBeforeMillisecondConversionCanOverflow() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DeliveryRetryPolicy(
                                3, Duration.ofSeconds(1), Duration.ofHours(25), () -> 0.0));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DeliveryRetryPolicy(
                                3,
                                Duration.ofSeconds(Long.MAX_VALUE),
                                Duration.ofSeconds(Long.MAX_VALUE),
                                () -> 0.0));
    }

    @Test
    void saturatesAtOneDayEvenWhenTheAttemptCounterIsVeryLarge() {
        DeliveryRetryPolicy bounded =
                new DeliveryRetryPolicy(
                        Integer.MAX_VALUE, Duration.ofMillis(1), Duration.ofDays(1), () -> 0.999);

        assertEquals(Duration.ofDays(1), bounded.delayAfterAttempt(Integer.MAX_VALUE));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsAttemptCountsThatCannotRepresentAClaimedDelivery(int attemptCount) {
        assertThrows(
                IllegalArgumentException.class, () -> retryPolicy.delayAfterAttempt(attemptCount));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        retryPolicy.shouldStopRetrying(
                                attemptCount, ErrorCode.DOWNSTREAM_UNAVAILABLE));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.0, 2.0, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsInvalidJitterInsteadOfProducingAnUnboundedOrNegativeDelay(double randomValue) {
        DeliveryRetryPolicy invalidJitter =
                new DeliveryRetryPolicy(
                        3, Duration.ofSeconds(2), Duration.ofSeconds(30), () -> randomValue);

        assertThrows(IllegalArgumentException.class, () -> invalidJitter.delayAfterAttempt(1));
    }
}
