package io.factorybridge.adapter.http.external;

import io.factorybridge.adapter.http.HttpRequestExecutor;
import io.factorybridge.application.port.ExternalDataClient;
import io.factorybridge.domain.ErrorCode;
import io.factorybridge.domain.FactoryBridgeException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class HttpExternalDataClient implements ExternalDataClient {
    private static final Set<String> SUPPORTED_SOURCES = Set.of("MES_A", "MES_B");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final HttpRequestExecutor requestExecutor;
    private final String baseUrl;

    public HttpExternalDataClient(
            @Value("${factorybridge.external.base-url:http://localhost:8090}") String baseUrl) {
        this.requestExecutor = new HttpRequestExecutor(REQUEST_TIMEOUT);
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @Override
    public String fetchMeasurement(String sourceSystem, String sourceRecordId) {
        validateSourceIdentity(sourceSystem, sourceRecordId);
        HttpRequest request =
                HttpRequest.newBuilder(measurementUri(sourceSystem, sourceRecordId))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
        try {
            HttpResponse<byte[]> response =
                    requestExecutor.sendWithDeadline(request, info -> new LimitedBodySubscriber());
            if (response.statusCode() == 404) {
                throw new FactoryBridgeException(
                        ErrorCode.RECORD_NOT_FOUND, "External source record does not exist.");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new FactoryBridgeException(
                        ErrorCode.EXTERNAL_SOURCE_UNAVAILABLE,
                        "External source returned HTTP " + response.statusCode() + ".");
            }
            // 解碼失敗就拒絕；replacement character 會悄悄改寫原始識別碼或量測內容。
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(response.body()))
                    .toString();
        } catch (IOException exception) {
            throw new FactoryBridgeException(
                    ErrorCode.EXTERNAL_SOURCE_UNAVAILABLE,
                    "External measurement could not be retrieved within the source contract.",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new FactoryBridgeException(
                    ErrorCode.EXTERNAL_SOURCE_UNAVAILABLE,
                    "External measurement request was interrupted.",
                    exception);
        }
    }

    private void validateSourceIdentity(String sourceSystem, String sourceRecordId) {
        if (sourceSystem == null || !SUPPORTED_SOURCES.contains(sourceSystem)) {
            throw new FactoryBridgeException(
                    ErrorCode.INVALID_SOURCE_SYSTEM,
                    ErrorCode.INVALID_SOURCE_SYSTEM.defaultMessage());
        }
        if (sourceRecordId == null || sourceRecordId.isBlank() || sourceRecordId.length() > 128) {
            throw new FactoryBridgeException(
                    ErrorCode.INVALID_REQUEST,
                    "Source record identifier must contain 1 to 128 characters.");
        }
    }

    private URI measurementUri(String sourceSystem, String sourceRecordId) {
        // 識別碼只能成為單一 path segment，不能改寫預先設定的來源主機或 API 路徑。
        String encodedRecordId =
                URLEncoder.encode(sourceRecordId, StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create(baseUrl + "/measurements/" + sourceSystem + "/" + encodedRecordId);
    }

    /** 取得外部資料時即限制記憶體用量，避免先收下無限大 response 才檢查大小。 */
    private static final class LimitedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int receivedBytes;

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            long incomingBytes = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (incomingBytes > MAX_RESPONSE_BYTES - receivedBytes) {
                subscription.cancel();
                delegate.onError(new IOException("External payload exceeds 64 KiB."));
                return;
            }
            receivedBytes += (int) incomingBytes;
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable throwable) {
            delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }
}
