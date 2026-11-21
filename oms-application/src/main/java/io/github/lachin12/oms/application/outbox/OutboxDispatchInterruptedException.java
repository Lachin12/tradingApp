package io.github.lachin12.oms.application.outbox;

/** Delivery was interrupted; the original claim remains leased for recovery after its expiry. */
public final class OutboxDispatchInterruptedException extends IllegalStateException {
    public OutboxDispatchInterruptedException(InterruptedException cause) {
        super("Outbox delivery was interrupted", cause);
    }
}
