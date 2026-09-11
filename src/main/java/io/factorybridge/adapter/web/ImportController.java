package io.factorybridge.adapter.web;

import io.factorybridge.application.ExternalMeasurementImportService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/imports")
public class ImportController {
    private final ExternalMeasurementImportService imports;

    public ImportController(ExternalMeasurementImportService imports) {
        this.imports = imports;
    }

    public record ImportMeasurementRequest(
            @NotBlank @Size(max = 128) String sourceSystem,
            @NotBlank @Size(max = 128) String sourceRecordId) {}

    @PostMapping
    @Operation(summary = "依來源識別主動取得 MES 資料，再執行相同 ingestion 流程")
    public ResponseEntity<IngestionResponse> importMeasurement(
            @Valid @RequestBody ImportMeasurementRequest request,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) String correlationId) {
        var result =
                imports.importMeasurement(
                        request.sourceSystem(), request.sourceRecordId(), correlationId);
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/measurements/" + result.measurementId()))
                .body(IngestionResponse.fromResult(result, correlationId));
    }
}
