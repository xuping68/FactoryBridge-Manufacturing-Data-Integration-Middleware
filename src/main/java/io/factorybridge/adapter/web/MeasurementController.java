package io.factorybridge.adapter.web;

import io.factorybridge.application.MeasurementIngestionService;
import io.factorybridge.application.port.*;
import io.factorybridge.domain.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/measurements")
public class MeasurementController {
    private static final Logger log = LoggerFactory.getLogger(MeasurementController.class);
    private final MeasurementIngestionService ingestion;
    private final MeasurementStore measurements;
    private final DeliveryStore deliveries;

    public MeasurementController(
            MeasurementIngestionService ingestion,
            MeasurementStore measurements,
            DeliveryStore deliveries) {
        this.ingestion = ingestion;
        this.measurements = measurements;
        this.deliveries = deliveries;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "接收一筆外部量測，保留 raw 後驗證與標準化",
            description = "202 = 已持久化並排入投遞；200 = 相同內容重送；409 = 相同 source key 但內容不同。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content =
                    @Content(
                            schema =
                                    @Schema(
                                            implementation =
                                                    io.factorybridge.adapter.http.external
                                                            .ExternalMeasurementDto.class)))
    @ApiResponse(responseCode = "202", description = "已接受，投遞尚未完成")
    @ApiResponse(responseCode = "200", description = "冪等重送")
    @ApiResponse(
            responseCode = "422",
            description = "量測內容驗證失敗",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "來源識別衝突",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<IngestionResponse> ingestMeasurement(
            HttpServletRequest request,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) String correlationId)
            throws IOException {
        var result =
                ingestion.ingestMeasurement(
                        BoundedPayloadReader.readMeasurementPayload(request), correlationId);
        log.info(
                "measurement_accepted stagingId={} measurementId={} duplicate={}",
                result.stagingId(),
                result.measurementId(),
                result.duplicate());
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/measurements/" + result.measurementId()))
                .body(IngestionResponse.fromResult(result, correlationId));
    }

    @GetMapping("/{measurementId}")
    @Operation(summary = "查詢標準化後的 canonical 量測")
    public MeasurementResponse getMeasurement(@PathVariable UUID measurementId) {
        return MeasurementResponse.fromStoredMeasurement(
                measurements
                        .findMeasurement(measurementId)
                        .orElseThrow(
                                () ->
                                        new FactoryBridgeException(
                                                ErrorCode.RECORD_NOT_FOUND,
                                                "Measurement was not found.")));
    }

    @GetMapping
    @Operation(summary = "依建立時間倒序列出最近量測，最多 100 筆")
    public List<MeasurementResponse> listMeasurements(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return measurements.listMeasurements(limit).stream()
                .map(MeasurementResponse::fromStoredMeasurement)
                .toList();
    }

    @GetMapping("/{measurementId}/deliveries")
    @Operation(summary = "查詢此量測各目的地的投遞狀態")
    public List<DeliveryResponse> listMeasurementDeliveries(@PathVariable UUID measurementId) {
        if (measurements.findMeasurement(measurementId).isEmpty()) {
            throw new FactoryBridgeException(
                    ErrorCode.RECORD_NOT_FOUND, "Measurement was not found.");
        }
        return deliveries.listDeliveries(measurementId).stream()
                .map(DeliveryResponse::fromDelivery)
                .toList();
    }
}
