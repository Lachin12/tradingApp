package io.github.lachin12.oms.application.outbox;

/**
 * Future transport adapter. Implementations must enforce a bounded timeout shorter than the lease.
 * Publication may be attempted more than once: a crash or lost lease can cause a duplicate.
 * Consumers must deduplicate by OutboxMessage.eventId(); transport acceptance is not execution.
 */
public interface EventPublisher {
    void publish(OutboxMessage message) throws Exception;
}
