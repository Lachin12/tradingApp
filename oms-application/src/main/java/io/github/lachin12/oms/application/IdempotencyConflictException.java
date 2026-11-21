package io.github.lachin12.oms.application;

public final class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() { super("Idempotency key was already used for a different request"); }
}
