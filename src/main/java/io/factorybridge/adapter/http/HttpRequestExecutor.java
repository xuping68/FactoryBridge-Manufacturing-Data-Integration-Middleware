package io.factorybridge.adapter.http;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** HTTP 基礎機制集中於邊界；成功與可否重試仍由各系統 adapter 判斷。 */
public final class HttpRequestExecutor {
    private final Duration timeout;
    private final HttpClient client;

    public HttpRequestExecutor(Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public <T> HttpResponse<T> sendWithDeadline(
            HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<T>> pendingResponse = client.sendAsync(request, bodyHandler);
        try {
            // 期限涵蓋 response body；只設定 request timeout 無法防止收到 headers 後停滯。
            return pendingResponse.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            pendingResponse.cancel(true);
            throw new HttpTimeoutException(
                    "HTTP exchange exceeded " + timeout.toMillis() + " milliseconds.");
        } catch (InterruptedException exception) {
            pendingResponse.cancel(true);
            throw exception;
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("HTTP exchange could not complete.", exception.getCause());
        }
    }
}
