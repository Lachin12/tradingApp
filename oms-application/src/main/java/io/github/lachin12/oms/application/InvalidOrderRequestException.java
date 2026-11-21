package io.github.lachin12.oms.application;

/** A caller-supplied request violates an input or domain validation rule. */
public final class InvalidOrderRequestException extends IllegalArgumentException {
    public InvalidOrderRequestException(String message) {
        super(message);
    }

    public InvalidOrderRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
