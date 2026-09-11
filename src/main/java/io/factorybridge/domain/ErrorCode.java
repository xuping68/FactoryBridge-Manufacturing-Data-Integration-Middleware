package io.factorybridge.domain;

/** 穩定代碼是對外契約；訊息可以補充情境，但呼叫端不應靠比對訊息分支。 */
public enum ErrorCode {
    INVALID_SOURCE_SYSTEM(ErrorCategory.VALIDATION, "Source system must be MES_A or MES_B."),
    MISSING_EQUIPMENT_ID(ErrorCategory.VALIDATION, "Equipment identifier is required."),
    INVALID_MEASUREMENT_VALUE(ErrorCategory.VALIDATION, "Measurement value is invalid."),
    UNSUPPORTED_UNIT(
            ErrorCategory.VALIDATION, "Measurement unit is not supported for this metric."),
    INVALID_EVENT_TIME(ErrorCategory.VALIDATION, "Measurement eventTime is invalid."),
    DUPLICATE_SOURCE_RECORD(ErrorCategory.BUSINESS, "Source record has already been ingested."),
    INVALID_PAYLOAD(ErrorCategory.VALIDATION, "Measurement payload is invalid."),
    MISSING_REQUIRED_FIELD(ErrorCategory.VALIDATION, "A required measurement field is missing."),
    UNSUPPORTED_METRIC(ErrorCategory.VALIDATION, "Measurement metric is not supported."),
    INVALID_QUALITY_STATUS(ErrorCategory.VALIDATION, "Measurement quality status is invalid."),
    FIELD_TOO_LONG(ErrorCategory.VALIDATION, "Measurement field exceeds its maximum length."),
    DOWNSTREAM_UNAVAILABLE(
            ErrorCategory.INTEGRATION, "Downstream system is temporarily unavailable."),
    DOWNSTREAM_REJECTED(ErrorCategory.INTEGRATION, "Downstream system rejected the measurement."),
    DATA_WAREHOUSE_WRITE_FAILED(ErrorCategory.INFRASTRUCTURE, "Data warehouse write failed."),
    EXTERNAL_SOURCE_UNAVAILABLE(
            ErrorCategory.INTEGRATION, "External source system is unavailable."),
    EXTERNAL_RECORD_MISMATCH(
            ErrorCategory.INTEGRATION,
            "External source returned a different record than requested."),
    RECORD_NOT_FOUND(ErrorCategory.BUSINESS, "Requested record does not exist."),
    DELIVERY_NOT_REPLAYABLE(ErrorCategory.BUSINESS, "Delivery is not eligible for replay."),
    STAGING_NOT_REPLAYABLE(ErrorCategory.BUSINESS, "Staging record is not eligible for replay."),
    INFRASTRUCTURE_UNAVAILABLE(
            ErrorCategory.INFRASTRUCTURE, "A required infrastructure service is unavailable."),
    INTERNAL_ERROR(ErrorCategory.INFRASTRUCTURE, "An unexpected internal error occurred."),
    INVALID_REQUEST(ErrorCategory.VALIDATION, "Request is invalid.");

    private final ErrorCategory category;
    private final String defaultMessage;

    ErrorCode(ErrorCategory category, String defaultMessage) {
        this.category = category;
        this.defaultMessage = defaultMessage;
    }

    public ErrorCategory category() {
        return category;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
