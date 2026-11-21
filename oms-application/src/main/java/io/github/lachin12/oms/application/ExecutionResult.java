package io.github.lachin12.oms.application;

import java.util.Objects;

/** Replays return the current snapshot, with no second execution or lifecycle event. */
public record ExecutionResult(StoredOrder storedOrder, boolean replay) {
    public ExecutionResult { Objects.requireNonNull(storedOrder, "stored order"); }
}
