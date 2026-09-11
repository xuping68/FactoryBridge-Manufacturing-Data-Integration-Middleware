package io.factorybridge.application;

import io.factorybridge.domain.FactoryBridgeException;
import java.util.UUID;

// 原始資料的查詢識別隨錯誤回傳，值班人員不必從 log 猜是哪一次輸入。
public final class IngestionRejectedException extends RuntimeException {
    private final UUID stagingId;
    private final FactoryBridgeException failure;

    public IngestionRejectedException(UUID stagingId, FactoryBridgeException failure) {
        super(failure.getMessage(), failure);
        this.stagingId = stagingId;
        this.failure = failure;
    }

    public UUID stagingId() {
        return stagingId;
    }

    public FactoryBridgeException failure() {
        return failure;
    }
}
