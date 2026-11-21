-- The receipt identifies one canonical execution payload across retries. It is
-- committed in the same transaction as its immutable execution fact and outbox.
ALTER TABLE order_events ADD CONSTRAINT order_events_order_execution_key
    UNIQUE (order_id, execution_id);

CREATE TABLE execution_receipts (
    execution_id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id) DEFERRABLE INITIALLY DEFERRED,
    quantity NUMERIC(28,8) NOT NULL
        CHECK (quantity BETWEEN 0.00000001 AND 99999999999999999999.99999999),
    price NUMERIC(28,8) NOT NULL
        CHECK (price BETWEEN 0.00000001 AND 99999999999999999999.99999999),
    executed_at TIMESTAMPTZ(6) NOT NULL,
    FOREIGN KEY (order_id, execution_id) REFERENCES order_events(order_id, execution_id)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE TRIGGER immutable_execution_receipts BEFORE UPDATE OR DELETE ON execution_receipts
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_mutation();

CREATE FUNCTION validate_execution_receipt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    fact order_events%ROWTYPE;
BEGIN
    SELECT * INTO fact FROM order_events
        WHERE order_id = NEW.order_id AND execution_id = NEW.execution_id AND event_type = 'EXECUTED';
    IF NOT FOUND OR fact.execution_quantity IS DISTINCT FROM NEW.quantity
        OR fact.execution_price IS DISTINCT FROM NEW.price
        OR fact.executed_at IS DISTINCT FROM NEW.executed_at THEN
        RAISE EXCEPTION 'Execution receipt must match its committed execution event'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER coherent_execution_receipt AFTER INSERT ON execution_receipts
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_execution_receipt();

-- Preserve existing fills and support replay after an upgrade without modifying
-- historical facts. Direct OrderStore fixtures may still append facts without a
-- receipt; the application execution boundary can claim those on a later replay.
INSERT INTO execution_receipts(execution_id, order_id, quantity, price, executed_at)
SELECT execution_id, order_id, execution_quantity, execution_price, executed_at
FROM order_events WHERE event_type = 'EXECUTED';
