package io.github.lachin12.oms.application.simulation;

import io.github.lachin12.oms.application.InvalidOrderRequestException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Version-three UUIDs provide repeatable identity; MD5 here is not a security mechanism. */
public final class NameBasedSimulationExecutionIds implements SimulationExecutionIds {
    @Override
    public UUID forStep(UUID scenarioId, UUID orderId, int step) {
        if (scenarioId == null || orderId == null) {
            throw new InvalidOrderRequestException("Scenario and order IDs are required");
        }
        if (step < 0 || step > 999) throw new InvalidOrderRequestException("Step must be 0..999");
        String identity = "trading-oms:simulation:v1|" + scenarioId + "|" + orderId + "|" + step;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }
}
