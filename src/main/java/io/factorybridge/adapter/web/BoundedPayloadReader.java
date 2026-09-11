package io.factorybridge.adapter.web;

import io.factorybridge.domain.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;

final class BoundedPayloadReader {
    static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private BoundedPayloadReader() {}

    static String readMeasurementPayload(HttpServletRequest request) throws IOException {
        // 對 chunked body 同樣施加上限，不只信任 Content-Length。
        byte[] payload = request.getInputStream().readNBytes(MAX_PAYLOAD_BYTES + 1);
        if (payload.length > MAX_PAYLOAD_BYTES) throw new PayloadTooLargeException();
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload))
                    .toString();
        } catch (CharacterCodingException invalidEncoding) {
            throw new FactoryBridgeException(
                    ErrorCode.INVALID_PAYLOAD,
                    "Measurement payload must use valid UTF-8.",
                    invalidEncoding);
        }
    }
}
