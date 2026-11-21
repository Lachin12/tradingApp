package io.github.lachin12.oms.application;

import io.github.lachin12.oms.domain.Execution;
import java.util.UUID;

/** Execution identity arbitration participates in the same transaction as the order update. */
public interface ExecutionStore {
    ExecutionClaim claimExecution(UUID orderId, Execution execution);
}
