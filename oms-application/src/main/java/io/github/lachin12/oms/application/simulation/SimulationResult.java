package io.github.lachin12.oms.application.simulation;

import io.github.lachin12.oms.application.StoredOrder;
import java.util.Objects;
import java.util.UUID;

public record SimulationResult(UUID executionId, StoredOrder storedOrder, boolean replay) {
    public SimulationResult {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(storedOrder, "storedOrder");
    }
}
