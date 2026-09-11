package io.factorybridge.config;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("factorybridge.delivery")
public record DeliveryProperties(
        @Min(1) @Max(100) int batchSize,
        @NotNull Duration leaseDuration,
        @Min(1) @Max(20) int maxAttempts,
        @NotNull Duration initialDelay,
        @NotNull Duration maxDelay) {
    public DeliveryProperties {
        // lease 須大於單次 HTTP/DB timeout，否則健康的工作也會被另一 worker 認領。
        if (leaseDuration != null && leaseDuration.compareTo(Duration.ofSeconds(15)) < 0) {
            throw new IllegalArgumentException("Delivery lease must be at least 15 seconds.");
        }
    }
}
