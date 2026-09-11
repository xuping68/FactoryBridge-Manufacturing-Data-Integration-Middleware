package io.factorybridge.application;

import io.factorybridge.domain.ErrorCode;
import java.time.Duration;
import java.util.function.DoubleSupplier;

public final class DeliveryRetryPolicy {
    private static final Duration MINIMUM_DELAY = Duration.ofMillis(1);
    private static final Duration MAXIMUM_DELAY = Duration.ofDays(1);
    private static final double JITTER_FRACTION = 0.2;
    private final int maxAttempts;
    private final long initialDelayMillis;
    private final long maximumDelayMillis;
    private final DoubleSupplier jitter;

    public DeliveryRetryPolicy(
            int maxAttempts, Duration initialDelay, Duration maxDelay, DoubleSupplier jitter) {
        if (maxAttempts < 1
                || initialDelay == null
                || maxDelay == null
                || jitter == null
                || initialDelay.compareTo(MINIMUM_DELAY) < 0
                || maxDelay.compareTo(MAXIMUM_DELAY) > 0
                || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException(
                    "Retry settings require positive attempts, a jitter source, "
                            + "and delays between 1 millisecond and 24 hours with initial delay <= maximum delay.");
        }
        this.maxAttempts = maxAttempts;
        this.initialDelayMillis = initialDelay.toMillis();
        this.maximumDelayMillis = maxDelay.toMillis();
        this.jitter = jitter;
    }

    public boolean shouldStopRetrying(int attemptCount, ErrorCode errorCode) {
        requirePositiveAttemptCount(attemptCount);
        return attemptCount >= maxAttempts
                || (errorCode != ErrorCode.DOWNSTREAM_UNAVAILABLE
                        && errorCode != ErrorCode.DATA_WAREHOUSE_WRITE_FAILED
                        && errorCode != ErrorCode.INFRASTRUCTURE_UNAVAILABLE);
    }

    public Duration delayAfterAttempt(int attemptCount) {
        requirePositiveAttemptCount(attemptCount);
        long delayMillis = initialDelayMillis;
        // 先判斷是否會超過上限再加倍；飽和後即停止，也不受極大 attemptCount 影響。
        for (int step = 1; step < attemptCount && delayMillis < maximumDelayMillis; step++) {
            delayMillis =
                    delayMillis > maximumDelayMillis / 2
                            ? maximumDelayMillis
                            : Math.min(delayMillis * 2, maximumDelayMillis);
        }
        double randomValue = jitter.getAsDouble();
        if (!Double.isFinite(randomValue) || randomValue < 0 || randomValue >= 1) {
            throw new IllegalArgumentException(
                    "Jitter source must return a finite value between 0 (inclusive) and 1 (exclusive).");
        }
        // 20% jitter 降低多 worker 同時重試；注入亂數來源讓測試可重現。
        long jitterMillis = (long) (delayMillis * JITTER_FRACTION * randomValue);
        return Duration.ofMillis(Math.min(maximumDelayMillis, delayMillis + jitterMillis));
    }

    private void requirePositiveAttemptCount(int attemptCount) {
        if (attemptCount < 1) {
            throw new IllegalArgumentException("Attempt count must be at least one.");
        }
    }
}
