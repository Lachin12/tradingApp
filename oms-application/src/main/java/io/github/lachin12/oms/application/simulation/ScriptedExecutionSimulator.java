package io.github.lachin12.oms.application.simulation;

import io.github.lachin12.oms.application.ExecutionResult;
import io.github.lachin12.oms.application.InvalidOrderRequestException;
import io.github.lachin12.oms.application.OmsService;
import io.github.lachin12.oms.domain.Execution;
import java.util.Objects;
import java.util.UUID;

/** Converts an explicit script step into a normal idempotent execution report. */
public final class ScriptedExecutionSimulator {
    private final OmsService orders;
    private final SimulationExecutionIds ids;

    public ScriptedExecutionSimulator(OmsService orders, SimulationExecutionIds ids) {
        this.orders = Objects.requireNonNull(orders, "orders");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    public SimulationResult execute(UUID orderId, SimulationStep step) {
        if (orderId == null || step == null) {
            throw new InvalidOrderRequestException("Order ID and simulation step are required");
        }
        UUID executionId = Objects.requireNonNull(ids.forStep(step.scenarioId(), orderId, step.step()),
                "Generated simulation execution ID");
        Execution execution = new Execution(executionId, step.quantity(), step.price(), step.executedAt());
        ExecutionResult result = orders.reportExecution(orderId, step.expectedVersion(), execution);
        return new SimulationResult(executionId, result.storedOrder(), result.replay());
    }
}
