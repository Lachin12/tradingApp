package io.github.lachin12.oms.api;

import io.github.lachin12.oms.application.OmsService;
import io.github.lachin12.oms.application.StoredOrder;
import io.github.lachin12.oms.application.SubmissionResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Simulated OMS acceptance and cancellation; acceptance does not execute an order")
public class OrderController {
    private final OmsService service;

    public OrderController(OmsService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Submit an order", description = "Creates an ACCEPTED order. Identical retries under the account-scoped key return the existing order; a different payload under that key returns 409.")
    @ApiResponse(responseCode = "201", description = "Order accepted, no execution implied")
    @ApiResponse(responseCode = "200", description = "Idempotent replay of an existing order")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "409", description = "Idempotency key conflicts with the request")
    public ResponseEntity<OrderResponse> submit(
            @Parameter(description = "1..128 visible ASCII characters, scoped to the account", required = true)
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[\\x21-\\x7E]{1,128}") String key,
            @Valid @RequestBody SubmitOrderRequest request) {
        SubmissionResult result = service.submit(request.toCommand(), key);
        StoredOrder stored = result.storedOrder();
        return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                .location(URI.create("/api/v1/orders/" + stored.order().id()))
                .eTag(etag(stored.version()))
                .header("Idempotency-Replayed", Boolean.toString(result.replay()))
                .body(OrderResponse.from(stored));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an order", description = "The ETag and version identify the current persisted order version.")
    public ResponseEntity<OrderResponse> get(@PathVariable("id") UUID id) {
        StoredOrder stored = service.get(id);
        return ResponseEntity.ok().eTag(etag(stored.version())).body(OrderResponse.from(stored));
    }

    @GetMapping
    @Operation(summary = "List orders for an account", description = "Bounded offset pagination. Limit is 1..100 and offset is 0..10000.")
    public List<OrderResponse> list(@RequestParam("accountId") UUID accountId,
                                   @RequestParam(name = "limit", defaultValue = "50") @Min(1) @Max(100) int limit,
                                   @RequestParam(name = "offset", defaultValue = "0") @Min(0) @Max(10000) int offset) {
        return service.list(accountId, limit, offset).stream().map(OrderResponse::from).toList();
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel the unexecuted portion of an order", description = "Requires the latest version in the body. A stale version or illegal lifecycle transition returns 409. Earlier executions remain immutable.")
    @ApiResponse(responseCode = "200", description = "Order cancelled")
    @ApiResponse(responseCode = "409", description = "Stale version or order cannot be cancelled")
    public ResponseEntity<OrderResponse> cancel(@PathVariable("id") UUID id,
                                                @Valid @RequestBody CancelOrderRequest request) {
        StoredOrder stored = service.cancel(id, request.expectedVersion(), request.reason());
        return ResponseEntity.ok().eTag(etag(stored.version())).body(OrderResponse.from(stored));
    }

    @GetMapping("/{id}/events")
    @Operation(summary = "Get immutable order lifecycle history", description = "Events are returned in per-order sequence order.")
    public ResponseEntity<List<OrderEventResponse>> events(@PathVariable("id") UUID id) {
        StoredOrder stored = service.get(id);
        return ResponseEntity.ok().eTag(etag(stored.version()))
                .body(stored.order().events().stream().map(OrderEventResponse::from).toList());
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }
}
