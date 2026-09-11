package io.factorybridge.adapter.web;

import io.factorybridge.application.IngestionRejectedException;
import io.factorybridge.domain.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.*;
import org.slf4j.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final Clock clock;

    public ApiExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(IngestionRejectedException.class)
    public ResponseEntity<ApiError> ingestionRejected(
            IngestionRejectedException exception, HttpServletRequest request) {
        var failure = exception.failure();
        logFailure(failure, exception.stagingId());
        return error(
                httpStatus(failure.errorCode()),
                failure.errorCode(),
                failure.getMessage(),
                request,
                exception.stagingId(),
                List.of());
    }

    @ExceptionHandler(FactoryBridgeException.class)
    public ResponseEntity<ApiError> knownFailure(
            FactoryBridgeException exception, HttpServletRequest request) {
        logFailure(exception, null);
        return error(
                httpStatus(exception.errorCode()),
                exception.errorCode(),
                exception.getMessage(),
                request,
                null,
                List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalidRequestBody(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        var violations =
                exception.getBindingResult().getFieldErrors().stream()
                        .map(
                                field ->
                                        new ApiError.FieldViolation(
                                                field.getField(), field.getDefaultMessage()))
                        .sorted(Comparator.comparing(ApiError.FieldViolation::field))
                        .toList();
        return error(
                HttpStatus.BAD_REQUEST,
                ErrorCode.INVALID_REQUEST,
                "Request fields are invalid.",
                request,
                null,
                violations);
    }

    @ExceptionHandler({
        HandlerMethodValidationException.class,
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class,
        java.io.IOException.class
    })
    public ResponseEntity<ApiError> invalidRequest(
            Exception exception, HttpServletRequest request) {
        return error(
                HttpStatus.BAD_REQUEST,
                ErrorCode.INVALID_REQUEST,
                "Request body or parameters are invalid.",
                request,
                null,
                List.of());
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ApiError> payloadTooLarge(
            PayloadTooLargeException exception, HttpServletRequest request) {
        return error(
                HttpStatus.PAYLOAD_TOO_LARGE,
                ErrorCode.INVALID_PAYLOAD,
                exception.getMessage(),
                request,
                null,
                List.of());
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> infrastructureUnavailable(
            Exception exception, HttpServletRequest request) {
        log.error("infrastructure_unavailable", exception);
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                ErrorCode.INFRASTRUCTURE_UNAVAILABLE.defaultMessage(),
                request,
                null,
                List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpectedFailure(
            Exception exception, HttpServletRequest request) {
        // Spring 的 404/405/415 等 transport 錯誤保留原狀態；不誤包成 HTTP 500。
        if (exception instanceof ErrorResponse frameworkError) {
            ErrorCode code =
                    frameworkError.getStatusCode().value() == 404
                            ? ErrorCode.RECORD_NOT_FOUND
                            : ErrorCode.INVALID_REQUEST;
            return error(
                    frameworkError.getStatusCode(),
                    code,
                    code.defaultMessage(),
                    request,
                    null,
                    List.of());
        }
        log.error("unexpected_request_failure", exception);
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.defaultMessage(),
                request,
                null,
                List.of());
    }

    private void logFailure(FactoryBridgeException failure, UUID stagingId) {
        if (failure.errorCode().category() == ErrorCategory.INFRASTRUCTURE
                || failure.getSuppressed().length > 0) {
            log.error(
                    "measurement_failed errorCode={} stagingId={}",
                    failure.errorCode(),
                    stagingId,
                    failure);
        } else {
            // 不記 raw/operator 資料，也不讓外部內容插入多行 log。
            log.warn("request_rejected errorCode={} stagingId={}", failure.errorCode(), stagingId);
        }
    }

    private ResponseEntity<ApiError> error(
            HttpStatusCode status,
            ErrorCode code,
            String message,
            HttpServletRequest request,
            UUID stagingId,
            List<ApiError.FieldViolation> violations) {
        var body =
                new ApiError(
                        code,
                        code.category(),
                        message,
                        (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE),
                        clock.instant(),
                        stagingId,
                        violations);
        return ResponseEntity.status(status).body(body);
    }

    private HttpStatus httpStatus(ErrorCode code) {
        return switch (code) {
            case INVALID_PAYLOAD, INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case RECORD_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DUPLICATE_SOURCE_RECORD, DELIVERY_NOT_REPLAYABLE, STAGING_NOT_REPLAYABLE ->
                    HttpStatus.CONFLICT;
            case EXTERNAL_SOURCE_UNAVAILABLE,
                    DOWNSTREAM_UNAVAILABLE,
                    INFRASTRUCTURE_UNAVAILABLE,
                    DATA_WAREHOUSE_WRITE_FAILED ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            case DOWNSTREAM_REJECTED, EXTERNAL_RECORD_MISMATCH -> HttpStatus.BAD_GATEWAY;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }
}
