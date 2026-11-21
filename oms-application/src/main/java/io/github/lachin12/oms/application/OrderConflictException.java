package io.github.lachin12.oms.application;

/** A valid command conflicts with the order's current business state. */
public final class OrderConflictException extends IllegalStateException {
    public OrderConflictException(String message) {
        super(message);
    }

    public OrderConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
