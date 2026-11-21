package io.github.lachin12.oms.application.outbox;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Durable delivery state. Callbacks participate in the caller's UnitOfWork.
 * Database time determines eligibility and lease expiry; tokens fence stale workers.
 */
public interface OutboxStore {
    /** Claim at most limit eligible messages, preserving per-order event sequencing. */
    List<DeliveryClaim> claim(int limit, Duration lease, UUID token);

    /** Acknowledge only a still-owned, unexpired claim. False means the lease was lost. */
    boolean acknowledge(DeliveryClaim claim);

    /** Delay a still-owned, unexpired claim after delivery failed. False means the lease was lost. */
    boolean reschedule(DeliveryClaim claim, Duration delay);
}
