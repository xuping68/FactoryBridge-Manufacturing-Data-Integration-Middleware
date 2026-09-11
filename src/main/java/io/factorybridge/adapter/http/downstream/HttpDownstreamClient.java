package io.factorybridge.adapter.http.downstream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.factorybridge.adapter.http.HttpRequestExecutor;
import io.factorybridge.application.port.DownstreamClient;
import io.factorybridge.domain.CanonicalMeasurement;
import io.factorybridge.domain.ErrorCode;
import io.factorybridge.domain.FactoryBridgeException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class HttpDownstreamClient implements DownstreamClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private final HttpRequestExecutor requestExecutor;
    private final ObjectMapper objectMapper;
    private final MeasurementTransformer transformer = new MeasurementTransformer();
    private final URI measurementEndpoint;

    public HttpDownstreamClient(
            @Value("${factorybridge.downstream.base-url:http://localhost:8090}") String baseUrl,
            ObjectMapper objectMapper) {
        this.requestExecutor = new HttpRequestExecutor(REQUEST_TIMEOUT);
        this.objectMapper = objectMapper;
        this.measurementEndpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/api/measurements");
    }

    @Override
    public void deliverMeasurement(
            UUID deliveryId,
            UUID measurementId,
            CanonicalMeasurement measurement,
            String correlationId) {
        DownstreamMeasurementDto payload =
                transformer.toDownstreamMeasurement(deliveryId, measurementId, measurement);
        HttpRequest request =
                HttpRequest.newBuilder(measurementEndpoint)
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", deliveryId.toString())
                        .header("X-Correlation-Id", correlationId)
                        .POST(HttpRequest.BodyPublishers.ofString(serializeMeasurement(payload)))
                        .build();
        try {
            HttpResponse<Void> response =
                    requestExecutor.sendWithDeadline(
                            request, HttpResponse.BodyHandlers.discarding());
            requireAcceptedResponse(response.statusCode());
        } catch (IOException exception) {
            throw new FactoryBridgeException(
                    ErrorCode.DOWNSTREAM_UNAVAILABLE,
                    "Downstream measurement delivery failed or timed out.",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new FactoryBridgeException(
                    ErrorCode.DOWNSTREAM_UNAVAILABLE,
                    "Downstream measurement delivery was interrupted.",
                    exception);
        }
    }

    private String serializeMeasurement(DownstreamMeasurementDto measurement) {
        try {
            return objectMapper.writeValueAsString(measurement);
        } catch (JsonProcessingException exception) {
            // 本地序列化錯誤屬於程式／契約問題；重試外部服務並不能修復此錯誤。
            throw new FactoryBridgeException(
                    ErrorCode.DOWNSTREAM_REJECTED,
                    "Measurement cannot be serialized to the downstream contract.",
                    exception);
        }
    }

    private void requireAcceptedResponse(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        // Retry 由持久化 delivery 狀態機統一控制，adapter 不自行重試，避免重複副作用。
        ErrorCode errorCode =
                statusCode == 408 || statusCode == 429 || statusCode >= 500
                        ? ErrorCode.DOWNSTREAM_UNAVAILABLE
                        : ErrorCode.DOWNSTREAM_REJECTED;
        throw new FactoryBridgeException(errorCode, "Downstream returned HTTP " + statusCode + ".");
    }
}
