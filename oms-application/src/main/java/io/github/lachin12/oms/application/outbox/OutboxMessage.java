package io.github.lachin12.oms.application.outbox;

import io.github.lachin12.oms.domain.OrderEvent;

/** Versioned delivery envelope. Retries retain the same immutable fact and stable event identity. */
public record OutboxMessage(OrderEvent event, int schemaVersion) {
    public OutboxMessage {
        if (event == null) throw new IllegalArgumentException("Event is required");
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported outbox schema version");
    }

    public String eventId() {
        return event.id();
    }
}
