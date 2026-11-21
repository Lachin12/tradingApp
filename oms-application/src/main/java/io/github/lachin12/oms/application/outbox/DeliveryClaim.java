package io.github.lachin12.oms.application.outbox;

import java.time.Instant;
import java.util.UUID;

/** An expiring, fenced right to deliver one message; attempt starts at one. */
public record DeliveryClaim(OutboxMessage message, UUID token, int attempt, Instant leaseUntil) {
    public DeliveryClaim {
        if (message == null || token == null || leaseUntil == null) {
            throw new IllegalArgumentException("Message, fencing token and lease expiry are required");
        }
        if (attempt < 1) throw new IllegalArgumentException("Attempt must be positive");
    }
}
