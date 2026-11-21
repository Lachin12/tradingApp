package io.github.lachin12.oms.application.outbox;

/** Infrastructure supplies whether the calling thread currently has an active database transaction. */
@FunctionalInterface
public interface TransactionContext {
    boolean active();
}
