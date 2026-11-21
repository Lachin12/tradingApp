package io.github.lachin12.oms.application.simulation;

import io.github.lachin12.oms.application.InvalidOrderRequestException;
import io.github.lachin12.oms.application.ExecutionTimestamps;
import io.github.lachin12.oms.domain.DecimalRules;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One caller-specified execution report; no price discovery, scheduling or implicit fills. */
public record SimulationStep(UUID scenarioId, int step, long expectedVersion,
                             BigDecimal quantity, BigDecimal price, Instant executedAt) {
    public SimulationStep {
        try {
            if (scenarioId == null || executedAt == null) {
                throw new IllegalArgumentException("Scenario ID and execution timestamp are required");
            }
            if (step < 0 || step > 999) throw new IllegalArgumentException("Step must be 0..999");
            if (expectedVersion < 0) throw new IllegalArgumentException("Expected version cannot be negative");
            quantity = DecimalRules.positive(quantity, "execution quantity");
            price = DecimalRules.positive(price, "execution price");
            executedAt = ExecutionTimestamps.normalize(executedAt);
        } catch (IllegalArgumentException ex) {
            throw new InvalidOrderRequestException(ex.getMessage(), ex);
        }
    }
}
