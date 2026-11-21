package io.github.lachin12.oms.api;

import io.github.lachin12.oms.application.simulation.ScriptedExecutionSimulator;
import io.github.lachin12.oms.application.simulation.SimulationResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/simulation/orders")
@ConditionalOnProperty(name = "oms.simulation.enabled", havingValue = "true", matchIfMissing = false)
@Tag(name = "Local manual simulation", description = "Opt-in local scenario steps with explicit prices and quantities; no exchange execution or matching engine")
public class SimulationController {
    private final ScriptedExecutionSimulator simulator;

    public SimulationController(ScriptedExecutionSimulator simulator) {
        this.simulator = simulator;
    }

    @PostMapping("/{orderId}/steps")
    @Operation(summary = "Apply an explicit local simulation step",
            description = "Available only when oms.simulation.enabled=true. Scenario, order and step derive a stable execution ID. An exact retry replays without another fill; conflicting reuse returns 409. New executions require the current order version.")
    @ApiResponse(responseCode = "200", description = "Execution recorded or an identical execution replayed")
    @ApiResponse(responseCode = "400", description = "Invalid simulation input")
    @ApiResponse(responseCode = "404", description = "Order not found, or simulation is disabled")
    @ApiResponse(responseCode = "409", description = "Execution identity conflict, stale version or illegal order state")
    public ResponseEntity<SimulationResponse> execute(@PathVariable("orderId") UUID orderId,
                                                      @Valid @RequestBody SimulationStepRequest request) {
        SimulationResult result = simulator.execute(orderId, request.toCommand());
        return ResponseEntity.ok()
                .eTag("\"" + result.storedOrder().version() + "\"")
                .header("Execution-Replayed", Boolean.toString(result.replay()))
                .body(SimulationResponse.from(result));
    }
}
