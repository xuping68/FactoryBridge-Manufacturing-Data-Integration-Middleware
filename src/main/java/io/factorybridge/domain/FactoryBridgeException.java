package io.factorybridge.domain;

import java.util.Objects;

/** 原始例外保留給內部診斷；API adapter 負責避免將底層細節暴露給呼叫端。 */
public final class FactoryBridgeException extends RuntimeException {
    private final ErrorCode errorCode;

    public FactoryBridgeException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public FactoryBridgeException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
