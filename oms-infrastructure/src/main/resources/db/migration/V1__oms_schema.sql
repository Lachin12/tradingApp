-- PostgreSQL is the only supported persistence engine. All decimal values are exact.
CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    name VARCHAR(128) NOT NULL CHECK (length(btrim(name)) > 0),
    active BOOLEAN NOT NULL
);

CREATE TABLE instruments (
    id UUID PRIMARY KEY,
    symbol VARCHAR(32) NOT NULL UNIQUE CHECK (length(btrim(symbol)) > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    tick_size NUMERIC(28,8) NOT NULL
        CHECK (tick_size BETWEEN 0.00000001 AND 99999999999999999999.99999999),
    quantity_step NUMERIC(28,8) NOT NULL
        CHECK (quantity_step BETWEEN 0.00000001 AND 99999999999999999999.99999999)
);

CREATE TABLE orders (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    account_name VARCHAR(128) NOT NULL CHECK (length(btrim(account_name)) > 0),
    account_active BOOLEAN NOT NULL CHECK (account_active),
    instrument_id UUID NOT NULL REFERENCES instruments(id),
    instrument_symbol VARCHAR(32) NOT NULL CHECK (length(btrim(instrument_symbol)) > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    tick_size NUMERIC(28,8) NOT NULL
        CHECK (tick_size BETWEEN 0.00000001 AND 99999999999999999999.99999999),
    quantity_step NUMERIC(28,8) NOT NULL
        CHECK (quantity_step BETWEEN 0.00000001 AND 99999999999999999999.99999999),
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY', 'SELL')),
    order_type VARCHAR(6) NOT NULL CHECK (order_type IN ('LIMIT', 'MARKET')),
    quantity NUMERIC(28,8) NOT NULL
        CHECK (quantity BETWEEN 0.00000001 AND 99999999999999999999.99999999),
    limit_price NUMERIC(28,8),
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('NEW', 'ACCEPTED', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'REJECTED')),
    filled_quantity NUMERIC(28,8) NOT NULL CHECK (filled_quantity >= 0 AND filled_quantity <= quantity),
    version BIGINT NOT NULL CHECK (version >= 0),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    last_sequence BIGINT NOT NULL CHECK (last_sequence >= 1 AND last_sequence = revision + 1),
    created_at TIMESTAMPTZ(6) NOT NULL,
    updated_at TIMESTAMPTZ(6) NOT NULL CHECK (updated_at >= created_at),
    UNIQUE (account_id, id),
    CHECK (quantity % quantity_step = 0),
    CHECK ((order_type = 'MARKET' AND limit_price IS NULL)
        OR (order_type = 'LIMIT' AND limit_price IS NOT NULL
            AND limit_price BETWEEN 0.00000001 AND 99999999999999999999.99999999
            AND limit_price % tick_size = 0)),
    CHECK ((status IN ('NEW', 'ACCEPTED', 'REJECTED') AND filled_quantity = 0)
        OR (status = 'PARTIALLY_FILLED' AND filled_quantity > 0 AND filled_quantity < quantity)
        OR (status = 'FILLED' AND filled_quantity = quantity)
        OR (status = 'CANCELLED' AND filled_quantity < quantity))
);
CREATE INDEX orders_account_page_idx ON orders(account_id, created_at DESC, id DESC);

CREATE TABLE order_events (
    order_id UUID NOT NULL REFERENCES orders(id),
    sequence BIGINT NOT NULL CHECK (sequence >= 1),
    event_type VARCHAR(16) NOT NULL
        CHECK (event_type IN ('SUBMITTED', 'ACCEPTED', 'EXECUTED', 'CANCELLED', 'REJECTED')),
    previous_status VARCHAR(16)
        CHECK (previous_status IN ('NEW', 'ACCEPTED', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'REJECTED')),
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('NEW', 'ACCEPTED', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'REJECTED')),
    occurred_at TIMESTAMPTZ(6) NOT NULL,
    cumulative_quantity NUMERIC(28,8) NOT NULL
        CHECK (cumulative_quantity BETWEEN 0 AND 99999999999999999999.99999999),
    execution_id UUID UNIQUE,
    execution_quantity NUMERIC(28,8),
    execution_price NUMERIC(28,8),
    executed_at TIMESTAMPTZ(6),
    reason VARCHAR(256),
    PRIMARY KEY (order_id, sequence),
    CHECK ((event_type = 'SUBMITTED') = (previous_status IS NULL)),
    CHECK ((event_type = 'SUBMITTED' AND sequence = 1 AND previous_status IS NULL
            AND status = 'NEW' AND cumulative_quantity = 0)
        OR (event_type = 'ACCEPTED' AND sequence = 2 AND previous_status = 'NEW'
            AND status = 'ACCEPTED' AND cumulative_quantity = 0)
        OR (event_type = 'EXECUTED' AND sequence >= 3
            AND previous_status IN ('ACCEPTED', 'PARTIALLY_FILLED')
            AND status IN ('PARTIALLY_FILLED', 'FILLED') AND cumulative_quantity > 0)
        OR (event_type = 'CANCELLED' AND sequence >= 2
            AND previous_status IN ('NEW', 'ACCEPTED', 'PARTIALLY_FILLED') AND status = 'CANCELLED'
            AND ((previous_status = 'PARTIALLY_FILLED' AND cumulative_quantity > 0)
                OR (previous_status IN ('NEW', 'ACCEPTED') AND cumulative_quantity = 0)))
        OR (event_type = 'REJECTED' AND sequence = 2 AND previous_status = 'NEW'
            AND status = 'REJECTED' AND cumulative_quantity = 0)),
    CHECK ((event_type = 'EXECUTED'
            AND execution_id IS NOT NULL AND execution_quantity IS NOT NULL
            AND execution_price IS NOT NULL AND executed_at IS NOT NULL
            AND execution_quantity BETWEEN 0.00000001 AND 99999999999999999999.99999999
            AND execution_price BETWEEN 0.00000001 AND 99999999999999999999.99999999
            AND executed_at <= occurred_at
            AND ((previous_status = 'ACCEPTED' AND cumulative_quantity = execution_quantity)
                OR (previous_status = 'PARTIALLY_FILLED' AND cumulative_quantity > execution_quantity)))
        OR (event_type <> 'EXECUTED' AND execution_id IS NULL AND execution_quantity IS NULL
            AND execution_price IS NULL AND executed_at IS NULL)),
    CHECK ((event_type IN ('CANCELLED', 'REJECTED') AND reason IS NOT NULL AND length(btrim(reason)) > 0)
        OR (event_type NOT IN ('CANCELLED', 'REJECTED') AND reason IS NULL)),
    CHECK (previous_status IS DISTINCT FROM 'NEW' OR sequence = 2),
    CHECK (previous_status IS DISTINCT FROM 'ACCEPTED' OR sequence = 3),
    CHECK (previous_status IS DISTINCT FROM 'PARTIALLY_FILLED' OR sequence >= 4)
);

-- A nondeferrable scoped key arbitrates concurrent claims. The order FK is deferred
-- because submission claims its key before validating reference data and inserting the order.
-- There is deliberately no immediate account FK: missing accounts must reach application validation.
CREATE TABLE submission_keys (
    account_id UUID NOT NULL,
    idempotency_key VARCHAR(128) COLLATE "C" NOT NULL CHECK (idempotency_key ~ '^[!-~]{1,128}$'),
    fingerprint CHAR(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    order_id UUID NOT NULL UNIQUE,
    PRIMARY KEY (account_id, idempotency_key),
    FOREIGN KEY (account_id, order_id) REFERENCES orders(account_id, id) DEFERRABLE INITIALLY DEFERRED
);

-- Immutable envelopes refer to immutable business events. Schema version specifies
-- the future publisher's serialization contract; publishing is not implemented yet.
CREATE TABLE outbox_events (
    event_id VARCHAR(64) PRIMARY KEY,
    order_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    created_at TIMESTAMPTZ(6) NOT NULL,
    UNIQUE (order_id, sequence),
    FOREIGN KEY (order_id, sequence) REFERENCES order_events(order_id, sequence),
    CHECK (event_id = order_id::text || ':' || sequence::text)
);

-- Future delivery bookkeeping can change without rewriting the business fact/envelope.
CREATE TABLE outbox_delivery (
    event_id VARCHAR(64) PRIMARY KEY REFERENCES outbox_events(event_id),
    published_at TIMESTAMPTZ(6),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error TEXT
);
CREATE INDEX outbox_delivery_pending_idx ON outbox_delivery(event_id) WHERE published_at IS NULL;

CREATE FUNCTION reject_immutable_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER immutable_order_events BEFORE UPDATE OR DELETE ON order_events
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_mutation();
CREATE TRIGGER immutable_outbox_events BEFORE UPDATE OR DELETE ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_mutation();
CREATE TRIGGER immutable_submission_keys BEFORE UPDATE OR DELETE ON submission_keys
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_mutation();

CREATE FUNCTION guard_order_update() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.account_id, NEW.account_name, NEW.account_active,
           NEW.instrument_id, NEW.instrument_symbol, NEW.currency, NEW.tick_size,
           NEW.quantity_step, NEW.side, NEW.order_type, NEW.quantity, NEW.limit_price, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.account_id, OLD.account_name, OLD.account_active,
           OLD.instrument_id, OLD.instrument_symbol, OLD.currency, OLD.tick_size,
           OLD.quantity_step, OLD.side, OLD.order_type, OLD.quantity, OLD.limit_price, OLD.created_at) THEN
        RAISE EXCEPTION 'Order terms and reference snapshots are immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.version <> OLD.version + 1 OR NEW.revision <> OLD.revision + 1
       OR NEW.last_sequence <> OLD.last_sequence + 1 OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'Order updates must append one event and advance the version' USING ERRCODE = '23514';
    END IF;
    IF NOT ((OLD.status = 'NEW' AND NEW.status IN ('ACCEPTED', 'REJECTED', 'CANCELLED'))
        OR (OLD.status IN ('ACCEPTED', 'PARTIALLY_FILLED') AND NEW.status = 'CANCELLED'
            AND NEW.filled_quantity = OLD.filled_quantity)
        OR (OLD.status IN ('ACCEPTED', 'PARTIALLY_FILLED')
            AND NEW.status IN ('PARTIALLY_FILLED', 'FILLED')
            AND NEW.filled_quantity > OLD.filled_quantity)) THEN
        RAISE EXCEPTION 'Illegal persisted order transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_order_update BEFORE UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION guard_order_update();

CREATE FUNCTION guard_event_sequence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.sequence > (SELECT last_sequence FROM orders WHERE id = NEW.order_id) THEN
        RAISE EXCEPTION 'Advance the order snapshot before appending its event' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_event_sequence BEFORE INSERT ON order_events
    FOR EACH ROW EXECUTE FUNCTION guard_event_sequence();

-- Deferred validation permits snapshot-first writes, while preventing a committed
-- snapshot without its full history and durable outbox references.
CREATE FUNCTION validate_order_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    current_order orders%ROWTYPE;
    last_event order_events%ROWTYPE;
    first_timestamp TIMESTAMPTZ;
    fact_count BIGINT;
    envelope_count BIGINT;
BEGIN
    SELECT * INTO STRICT current_order FROM orders WHERE id = NEW.id;
    SELECT count(*), min(occurred_at) INTO fact_count, first_timestamp
        FROM order_events WHERE order_id = NEW.id;
    SELECT count(*) INTO envelope_count FROM outbox_events WHERE order_id = NEW.id;
    SELECT * INTO last_event FROM order_events
        WHERE order_id = NEW.id AND sequence = current_order.last_sequence;
    IF fact_count <> current_order.last_sequence OR envelope_count <> fact_count
        OR last_event.order_id IS NULL OR last_event.status <> current_order.status
        OR last_event.cumulative_quantity <> current_order.filled_quantity
        OR last_event.occurred_at <> current_order.updated_at
        OR first_timestamp <> current_order.created_at THEN
        RAISE EXCEPTION 'Order snapshot, history and outbox must commit together' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER coherent_order_history AFTER INSERT OR UPDATE ON orders
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_order_history();
