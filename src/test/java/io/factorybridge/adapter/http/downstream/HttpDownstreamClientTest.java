package io.factorybridge.adapter.http.downstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpServer;
import io.factorybridge.domain.CanonicalMeasurement;
import io.factorybridge.domain.ErrorCode;
import io.factorybridge.domain.FactoryBridgeException;
import io.factorybridge.domain.MetricType;
import io.factorybridge.domain.QualityStatus;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HttpDownstreamClientTest {
    private final ObjectMapper objectMapper =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final UUID deliveryId = UUID.fromString("0146f7de-652c-45ce-9a60-61daf397a807");
    private final UUID measurementId = UUID.fromString("80c25c95-86c6-4200-9cc1-c25f57b072e6");
    private HttpServer downstreamServer;
    private ExecutorService serverExecutor;
    private HttpDownstreamClient client;

    @BeforeEach
    void startDownstreamServer() throws Exception {
        downstreamServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        downstreamServer.setExecutor(serverExecutor);
        downstreamServer.start();
        client =
                new HttpDownstreamClient(
                        "http://127.0.0.1:" + downstreamServer.getAddress().getPort(),
                        objectMapper);
    }

    @AfterEach
    void stopDownstreamServer() {
        downstreamServer.stop(0);
        serverExecutor.shutdownNow();
    }

    @Test
    void sendsVersionedContractAndStableIdempotencyHeadersOverHttp() throws Exception {
        AtomicReference<String> receivedPayload = new AtomicReference<>();
        AtomicReference<String> receivedKey = new AtomicReference<>();
        AtomicReference<String> correlationId = new AtomicReference<>();
        downstreamServer.createContext(
                "/api/measurements",
                exchange -> {
                    receivedPayload.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    receivedKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                    correlationId.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
                    exchange.sendResponseHeaders(202, -1);
                    exchange.close();
                });

        client.deliverMeasurement(deliveryId, measurementId, canonicalMeasurement(), "corr-123");

        JsonNode json = objectMapper.readTree(receivedPayload.get());
        assertThat(receivedKey.get()).isEqualTo(deliveryId.toString());
        assertThat(correlationId.get()).isEqualTo("corr-123");
        assertThat(json.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(json.path("messageId").asText()).isEqualTo(deliveryId.toString());
        assertThat(json.path("measurementId").asText()).isEqualTo(measurementId.toString());
        assertThat(json.at("/asset/equipmentId").asText()).isEqualTo("EQ-CT-003");
        assertThat(json.at("/measurement/value").decimalValue()).isEqualByComparingTo("35.2");
        assertThat(json.at("/measurement/unit").asText()).isEqualTo("C");
        assertThat(json.at("/measurement/measuredAt").asText()).isEqualTo("2026-09-10T06:30:22Z");
        assertThat(json.at("/traceability/sourceRecordId").asText()).isEqualTo("MES-A-0001");
        assertThat(json.has("eventTime")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "400,DOWNSTREAM_REJECTED",
        "409,DOWNSTREAM_REJECTED",
        "422,DOWNSTREAM_REJECTED",
        "302,DOWNSTREAM_REJECTED",
        "408,DOWNSTREAM_UNAVAILABLE",
        "429,DOWNSTREAM_UNAVAILABLE",
        "500,DOWNSTREAM_UNAVAILABLE",
        "503,DOWNSTREAM_UNAVAILABLE"
    })
    void classifiesResponseWithoutRetryingInsideTheAdapter(int status, ErrorCode expectedCode) {
        AtomicInteger attempts = new AtomicInteger();
        downstreamServer.createContext(
                "/api/measurements",
                exchange -> {
                    attempts.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(status, -1);
                    exchange.close();
                });

        assertThatThrownBy(
                        () ->
                                client.deliverMeasurement(
                                        deliveryId,
                                        measurementId,
                                        canonicalMeasurement(),
                                        "corr-123"))
                .isInstanceOfSatisfying(
                        FactoryBridgeException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(expectedCode));
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void timesOutEvenWhenHeadersArriveButResponseBodyNeverFinishes() {
        CountDownLatch releaseBody = new CountDownLatch(1);
        downstreamServer.createContext(
                "/api/measurements",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(200, 10);
                    exchange.getResponseBody().write('x');
                    exchange.getResponseBody().flush();
                    try {
                        releaseBody.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });

        long startedAt = System.nanoTime();
        try {
            assertThatThrownBy(
                            () ->
                                    client.deliverMeasurement(
                                            deliveryId,
                                            measurementId,
                                            canonicalMeasurement(),
                                            "corr-123"))
                    .isInstanceOfSatisfying(
                            FactoryBridgeException.class,
                            exception ->
                                    assertThat(exception.errorCode())
                                            .isEqualTo(ErrorCode.DOWNSTREAM_UNAVAILABLE));
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - startedAt))
                    .isLessThan(java.time.Duration.ofSeconds(4));
        } finally {
            releaseBody.countDown();
        }
    }

    private CanonicalMeasurement canonicalMeasurement() {
        return new CanonicalMeasurement(
                "EQ-CT-003",
                "COATING_MACHINE",
                "KH01",
                "CELL-LINE-01",
                "COATING-03",
                "LOT-1",
                "BATCH-1",
                MetricType.TEMPERATURE,
                new BigDecimal("35.2"),
                "C",
                Instant.parse("2026-09-10T06:30:22Z"),
                QualityStatus.GOOD,
                "MES_A",
                "MES-A-0001");
    }
}
