package io.factorybridge.config;

import io.factorybridge.application.*;
import io.factorybridge.application.port.*;
import io.factorybridge.domain.MeasurementNormalizer;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration
@EnableConfigurationProperties(DeliveryProperties.class)
public class ApplicationConfiguration {
    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    MeasurementNormalizer measurementNormalizer() {
        return new MeasurementNormalizer();
    }

    @Bean
    MeasurementIngestionService measurementIngestionService(
            StagingStore staging,
            MeasurementPayloadDecoder decoder,
            MeasurementNormalizer normalizer,
            MeasurementStore measurements,
            Clock clock) {
        return new MeasurementIngestionService(staging, decoder, normalizer, measurements, clock);
    }

    @Bean
    ExternalMeasurementImportService externalMeasurementImportService(
            ExternalDataClient client, MeasurementIngestionService ingestion) {
        return new ExternalMeasurementImportService(client, ingestion);
    }

    @Bean
    StagingReplayService stagingReplayService(
            StagingStore staging, MeasurementIngestionService ingestion) {
        return new StagingReplayService(staging, ingestion);
    }

    @Bean
    DeliveryRetryPolicy deliveryRetryPolicy(DeliveryProperties settings) {
        return new DeliveryRetryPolicy(
                settings.maxAttempts(),
                settings.initialDelay(),
                settings.maxDelay(),
                () -> ThreadLocalRandom.current().nextDouble());
    }

    @Bean
    DeliveryDispatcher deliveryDispatcher(
            DeliveryStore deliveries,
            MeasurementStore measurements,
            DownstreamClient downstream,
            WarehouseWriter warehouse,
            DeliveryRetryPolicy retryPolicy,
            Clock clock,
            DeliveryProperties settings) {
        return new DeliveryDispatcher(
                deliveries,
                measurements,
                downstream,
                warehouse,
                retryPolicy,
                clock,
                settings.leaseDuration());
    }

    @Bean
    OpenAPI factoryBridgeOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("FactoryBridge — Manufacturing Data Integration Middleware")
                                .version("v1")
                                .description(
                                        "原始資料留存、標準化、冪等接收與可追蹤投遞。202 僅代表 durable acceptance；請查詢 deliveries 確認交付結果。"));
    }
}
