package io.github.lachin12.oms.api;

import io.github.lachin12.oms.application.simulation.SimulationStep;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Manual local simulation input; it is never accepted by the regular order routes. */
public record SimulationStepRequest(
        @NotNull @Schema(description = "Stable scenario identity reused for retries") UUID scenarioId,
        @NotNull @Min(0) @Max(999)
        @Schema(description = "Stable step number within the scenario, from 0 to 999", example = "0") Integer step,
        @NotNull @PositiveOrZero
        @Schema(description = "Current order version for a new execution; exact replays may use the original version", example = "0")
        Long expectedVersion,
        @NotNull @Schema(type = "string", example = "4.00000000",
                description = "Explicit positive execution quantity; exact decimal and instrument step rules apply")
        BigDecimal quantity,
        @NotNull @Schema(type = "string", example = "100.25000000",
                description = "Explicit positive execution price; exact decimal, tick and order-limit rules apply")
        BigDecimal price,
        @NotNull @Schema(description = "Explicit simulated execution time, no later than the recording clock; UTC year 0001 through 9999, normalized to microsecond precision")
        Instant executedAt) {

    SimulationStep toCommand() {
        return new SimulationStep(scenarioId, step, expectedVersion, quantity, price, executedAt);
    }
}
