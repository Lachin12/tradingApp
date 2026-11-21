package io.github.lachin12.oms.api;

import io.github.lachin12.oms.application.simulation.SimulationResult;
import java.util.UUID;

public record SimulationResponse(UUID executionId, boolean replay, OrderResponse order) {
    static SimulationResponse from(SimulationResult result) {
        return new SimulationResponse(result.executionId(), result.replay(), OrderResponse.from(result.storedOrder()));
    }
}
