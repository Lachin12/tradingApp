package io.github.lachin12.oms.application.simulation;

import java.util.UUID;

/** Simulation identity is determined by scenario, order and step, independently of report payload. */
@FunctionalInterface
public interface SimulationExecutionIds {
    UUID forStep(UUID scenarioId, UUID orderId, int step);
}
