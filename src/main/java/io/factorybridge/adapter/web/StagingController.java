package io.factorybridge.adapter.web;

import io.factorybridge.application.StagingReplayService;
import io.factorybridge.application.model.StagingRecord;
import io.factorybridge.application.port.StagingStore;
import io.factorybridge.domain.*;
import io.swagger.v3.oas.annotations.Operation;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staging")
public class StagingController {
    private final StagingStore staging;
    private final StagingReplayService replay;

    public StagingController(StagingStore staging, StagingReplayService replay) {
        this.staging = staging;
        this.replay = replay;
    }

    @GetMapping("/{stagingId}")
    @Operation(summary = "查詢 raw payload 與本次處理結果（本機 Demo 除錯用）")
    public StagingRecord getStagingRecord(@PathVariable UUID stagingId) {
        return staging.findStagingRecord(stagingId)
                .orElseThrow(
                        () ->
                                new FactoryBridgeException(
                                        ErrorCode.RECORD_NOT_FOUND,
                                        "Staging record was not found."));
    }

    @PostMapping("/{stagingId}/replays")
    @Operation(
            summary = "以原始 raw 建立新的處理嘗試；保留歷史，canonical 仍去重",
            description = "若原內容本身無效，重跑仍會失敗。需修正來源後重新 POST；不允許覆寫歷史 raw。")
    public ResponseEntity<IngestionResponse> replayStagingRecord(
            @PathVariable UUID stagingId,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) String correlationId) {
        var result = replay.replayStagingRecord(stagingId, correlationId);
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/measurements/" + result.measurementId()))
                .body(IngestionResponse.fromResult(result, correlationId));
    }
}
