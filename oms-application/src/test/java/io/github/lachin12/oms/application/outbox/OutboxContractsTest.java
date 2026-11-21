package io.github.lachin12.oms.application.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxContractsTest {
    @Test
    void envelopePreservesStableEventIdentityAndImmutableFact() {
        OutboxMessage message = OutboxDispatcherTest.message(10);

        assertEquals(message.event().id(), message.eventId());
        assertEquals(message, new OutboxMessage(message.event(), 1));
        assertEquals(1, message.schemaVersion());
    }

    @Test
    void rejectsMissingEventAndUnknownSchema() {
        assertThrows(IllegalArgumentException.class, () -> new OutboxMessage(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new OutboxMessage(OutboxDispatcherTest.message(10).event(), 0));
        assertThrows(IllegalArgumentException.class, () -> new OutboxMessage(OutboxDispatcherTest.message(10).event(), 2));
    }

    @Test
    void claimRequiresFencingIdentityPositiveAttemptAndExpiry() {
        OutboxMessage message = OutboxDispatcherTest.message(10);
        UUID token = new UUID(0, 1);
        Instant expiry = Instant.parse("2026-01-01T12:00:00Z");

        assertThrows(IllegalArgumentException.class, () -> new DeliveryClaim(null, token, 1, expiry));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryClaim(message, null, 1, expiry));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryClaim(message, token, 0, expiry));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryClaim(message, token, -1, expiry));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryClaim(message, token, 1, null));
    }

    @Test
    void resultRequiresExactlyOneOutcomeForEachClaim() {
        assertThrows(IllegalArgumentException.class, () -> new DispatchResult(1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new DispatchResult(1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DispatchResult(-1, -1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DispatchResult(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 1));
        assertEquals(3, new DispatchResult(3, 1, 1, 1).claimed());
    }
}
