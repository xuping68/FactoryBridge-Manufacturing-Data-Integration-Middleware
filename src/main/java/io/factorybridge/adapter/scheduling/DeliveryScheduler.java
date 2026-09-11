package io.factorybridge.adapter.scheduling;

import io.factorybridge.application.DeliveryDispatcher;
import io.factorybridge.config.DeliveryProperties;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "factorybridge.delivery.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DeliveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(DeliveryScheduler.class);
    private final DeliveryDispatcher dispatcher;
    private final DeliveryProperties settings;

    public DeliveryScheduler(DeliveryDispatcher dispatcher, DeliveryProperties settings) {
        this.dispatcher = dispatcher;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${factorybridge.delivery.poll-delay-ms:1000}")
    public void dispatchPendingMeasurements() {
        try {
            int claimed = dispatcher.dispatchDueDeliveries(settings.batchSize());
            if (claimed > 0) log.info("delivery_poll_completed claimed={}", claimed);
        } catch (RuntimeException failure) {
            // scheduler 是最後錯誤邊界；記錄 stack trace 並讓下一輪從 durable lease 恢復。
            log.error("delivery_poll_failed; pending or expired leases will be retried", failure);
        }
    }
}
