-- The immutable history and outbox envelopes remain unchanged. These are mutable
-- delivery fields only; time and lease decisions use the PostgreSQL clock.
ALTER TABLE outbox_delivery
    ADD COLUMN claim_token UUID,
    ADD COLUMN lease_until TIMESTAMPTZ(6),
    ADD COLUMN next_attempt_at TIMESTAMPTZ(6) NOT NULL DEFAULT '-infinity';

ALTER TABLE outbox_delivery
    ADD CONSTRAINT delivery_lease_pair CHECK ((claim_token IS NULL) = (lease_until IS NULL)),
    ADD CONSTRAINT published_delivery_has_no_lease CHECK (published_at IS NULL OR claim_token IS NULL);

CREATE INDEX outbox_delivery_due_idx
    ON outbox_delivery(next_attempt_at, lease_until, event_id) WHERE published_at IS NULL;

-- V1 already indexes outbox_events(order_id, sequence) through its UNIQUE constraint,
-- supporting the earlier-unpublished head lookup. No clock-dependent index predicate.

-- Repair missing metadata conservatively: never assume an event was delivered.
-- Existing rows, including published timestamps and attempt counts, are preserved.
INSERT INTO outbox_delivery(event_id)
SELECT envelope.event_id FROM outbox_events envelope
LEFT JOIN outbox_delivery delivery ON delivery.event_id = envelope.event_id
WHERE delivery.event_id IS NULL;

-- Complete the one-to-one relationship. It is deferred because the order adapter
-- inserts each immutable envelope before its delivery row within the same transaction.
ALTER TABLE outbox_events ADD CONSTRAINT outbox_has_delivery
    FOREIGN KEY (event_id) REFERENCES outbox_delivery(event_id)
    DEFERRABLE INITIALLY DEFERRED;
