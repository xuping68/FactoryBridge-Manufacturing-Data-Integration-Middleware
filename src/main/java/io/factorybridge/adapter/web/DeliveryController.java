package io.factorybridge.adapter.web;

import io.factorybridge.application.DeliveryDispatcher;
import io.factorybridge.application.port.DeliveryStore;
import io.factorybridge.domain.*;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/deliveries")
public class DeliveryController {
    private final DeliveryStore deliveries;
    private final DeliveryDispatcher dispatcher;

    public DeliveryController(DeliveryStore deliveries, DeliveryDispatcher dispatcher) {
        this.deliveries = deliveries;
        this.dispatcher = dispatcher;
    }

    @GetMapping("/{deliveryId}")
    @Operation(summary = "查詢投遞、重試次數與最後錯誤")
    public DeliveryResponse getDelivery(@PathVariable UUID deliveryId) {
        return DeliveryResponse.fromDelivery(
                deliveries
                        .findDelivery(deliveryId)
                        .orElseThrow(
                                () ->
                                        new FactoryBridgeException(
                                                ErrorCode.RECORD_NOT_FOUND,
                                                "Delivery was not found.")));
    }

    @PostMapping("/{deliveryId}/replays")
    @Operation(summary = "將 DEAD 投遞重新排入佇列，保留相同 idempotency key")
    public ResponseEntity<DeliveryResponse> replayDeadDelivery(@PathVariable UUID deliveryId) {
        return ResponseEntity.accepted()
                .body(DeliveryResponse.fromDelivery(dispatcher.replayDeadDelivery(deliveryId)));
    }
}
