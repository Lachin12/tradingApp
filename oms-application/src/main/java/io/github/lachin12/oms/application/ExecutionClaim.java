package io.github.lachin12.oms.application;

import io.github.lachin12.oms.domain.Execution;
import java.util.Objects;
import java.util.UUID;

public record ExecutionClaim(UUID orderId, Execution execution, boolean owner) {
    public ExecutionClaim {
        Objects.requireNonNull(orderId, "order id");
        Objects.requireNonNull(execution, "execution");
    }
}
