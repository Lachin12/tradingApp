package io.github.lachin12.oms.application;

public final class ExecutionConflictException extends RuntimeException {
    public ExecutionConflictException() {
        super("Execution ID was already used for a different order or report");
    }
}
