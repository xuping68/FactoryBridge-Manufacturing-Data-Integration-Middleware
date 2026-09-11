package io.factorybridge.domain;

/** 錯誤類別供應用層決定處理策略；HTTP status 不屬於領域模型。 */
public enum ErrorCategory {
    VALIDATION,
    BUSINESS,
    INTEGRATION,
    INFRASTRUCTURE
}
