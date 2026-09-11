package io.factorybridge.adapter.http.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.factorybridge.domain.ErrorCode;
import io.factorybridge.domain.FactoryBridgeException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpExternalDataClientTest {
    private HttpServer sourceServer;
    private HttpExternalDataClient client;

    @BeforeEach
    void startSourceServer() throws Exception {
        sourceServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sourceServer.start();
        client =
                new HttpExternalDataClient(
                        "http://127.0.0.1:" + sourceServer.getAddress().getPort());
    }

    @AfterEach
    void stopSourceServer() {
        sourceServer.stop(0);
    }

    @Test
    void fetchesRawMeasurementWithEncodedSingleRecordSegment() {
        AtomicReference<String> requestPath = new AtomicReference<>();
        String raw = "{ \"sourceSystem\" : \"MES_A\", \"value\" : \"95.36\" }";
        sourceServer.createContext(
                "/measurements/",
                exchange -> {
                    requestPath.set(exchange.getRequestURI().getRawPath());
                    byte[] body = raw.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });

        assertThat(client.fetchMeasurement("MES_A", "record/with space")).isEqualTo(raw);
        assertThat(requestPath.get()).isEqualTo("/measurements/MES_A/record%2Fwith%20space");
    }

    @Test
    void rejectsUnconfiguredSourceBeforeCallingHttp() {
        assertThatThrownBy(() -> client.fetchMeasurement("https://other-host", "r1"))
                .isInstanceOfSatisfying(
                        FactoryBridgeException.class,
                        exception ->
                                assertThat(exception.errorCode())
                                        .isEqualTo(ErrorCode.INVALID_SOURCE_SYSTEM));
    }

    @Test
    void mapsMissingSourceRecordToBusinessError() {
        assertThatThrownBy(() -> client.fetchMeasurement("MES_A", "missing"))
                .isInstanceOfSatisfying(
                        FactoryBridgeException.class,
                        exception ->
                                assertThat(exception.errorCode())
                                        .isEqualTo(ErrorCode.RECORD_NOT_FOUND));
    }

    @Test
    void boundsExternalResponseMemoryBeforeParsingJson() {
        sourceServer.createContext(
                "/measurements/",
                exchange -> {
                    byte[] body = new byte[65_537];
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });

        assertThatThrownBy(() -> client.fetchMeasurement("MES_A", "r1"))
                .isInstanceOfSatisfying(
                        FactoryBridgeException.class,
                        exception ->
                                assertThat(exception.errorCode())
                                        .isEqualTo(ErrorCode.EXTERNAL_SOURCE_UNAVAILABLE));
    }

    @Test
    void rejectsMalformedUtf8InsteadOfReplacingSourceData() {
        sourceServer.createContext(
                "/measurements/",
                exchange -> {
                    byte[] body = {
                        '{', '"', 'v', 'a', 'l', 'u', 'e', '"', ':', '"', (byte) 0xff, '"', '}'
                    };
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });

        assertThatThrownBy(() -> client.fetchMeasurement("MES_A", "r1"))
                .isInstanceOfSatisfying(
                        FactoryBridgeException.class,
                        exception ->
                                assertThat(exception.errorCode())
                                        .isEqualTo(ErrorCode.EXTERNAL_SOURCE_UNAVAILABLE));
    }
}
