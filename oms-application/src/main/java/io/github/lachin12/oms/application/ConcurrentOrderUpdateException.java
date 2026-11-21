package io.github.lachin12.oms.application;

public final class ConcurrentOrderUpdateException extends RuntimeException {
    public ConcurrentOrderUpdateException() { super("Order version changed; reload before retrying"); }
}
