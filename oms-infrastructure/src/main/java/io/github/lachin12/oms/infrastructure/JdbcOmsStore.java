package io.github.lachin12.oms.infrastructure;

import io.github.lachin12.oms.application.ConcurrentOrderUpdateException;
import io.github.lachin12.oms.application.ExecutionClaim;
import io.github.lachin12.oms.application.ExecutionStore;
import io.github.lachin12.oms.application.OrderStore;
import io.github.lachin12.oms.application.ReferenceData;
import io.github.lachin12.oms.application.StoredOrder;
import io.github.lachin12.oms.application.SubmissionClaim;
import io.github.lachin12.oms.application.SubmissionStore;
import io.github.lachin12.oms.domain.Account;
import io.github.lachin12.oms.domain.Execution;
import io.github.lachin12.oms.domain.Instrument;
import io.github.lachin12.oms.domain.Order;
import io.github.lachin12.oms.domain.OrderEvent;
import io.github.lachin12.oms.domain.OrderEventType;
import io.github.lachin12.oms.domain.OrderStatus;
import io.github.lachin12.oms.domain.OrderType;
import io.github.lachin12.oms.domain.Side;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** PostgreSQL adapter; snapshots, history and outbox references share the caller's transaction. */
public final class JdbcOmsStore implements OrderStore, SubmissionStore, ReferenceData, ExecutionStore {
    private static final String SELECT_EVENTS = """
            SELECT o.*, e.sequence AS event_sequence, e.event_type, e.previous_status,
                   e.status AS event_status, e.occurred_at, e.cumulative_quantity,
                   e.execution_id, e.execution_quantity, e.execution_price, e.executed_at, e.reason
            FROM orders o LEFT JOIN order_events e ON e.order_id = o.id
            """;
    private final JdbcTemplate jdbc;

    public JdbcOmsStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<Account> findAccount(UUID id) {
        return jdbc.query("SELECT id, name, active FROM accounts WHERE id = ?",
                (rs, row) -> new Account(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getBoolean("active")), id).stream().findFirst();
    }

    @Override
    public Optional<Instrument> findInstrument(UUID id) {
        return jdbc.query("SELECT * FROM instruments WHERE id = ?", (rs, row) ->
                new Instrument(rs.getObject("id", UUID.class), rs.getString("symbol"),
                        Currency.getInstance(rs.getString("currency")), rs.getBigDecimal("tick_size"),
                        rs.getBigDecimal("quantity_step")), id).stream().findFirst();
    }

    @Override
    public SubmissionClaim claim(UUID accountId, String key, String fingerprint, UUID proposedOrderId) {
        requireTransaction();
        int inserted = jdbc.update("""
                INSERT INTO submission_keys(account_id, idempotency_key, fingerprint, order_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (account_id, idempotency_key) DO NOTHING
                """, accountId, key, fingerprint, proposedOrderId);
        // A separate statement is essential. A CTE's original snapshot might not see
        // the row whose competing INSERT this statement has just waited to resolve.
        return jdbc.queryForObject("""
                SELECT order_id, fingerprint FROM submission_keys
                WHERE account_id = ? AND idempotency_key = ?
                """, (rs, row) -> new SubmissionClaim(rs.getObject("order_id", UUID.class),
                        rs.getString("fingerprint"), inserted == 1), accountId, key);
    }

    @Override
    public ExecutionClaim claimExecution(UUID orderId, Execution execution) {
        requireTransaction();
        Objects.requireNonNull(orderId, "order id");
        Objects.requireNonNull(execution, "execution");
        int inserted = jdbc.update("""
                INSERT INTO execution_receipts(execution_id, order_id, quantity, price, executed_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (execution_id) DO NOTHING
                """, execution.id(), orderId, execution.quantity(), execution.price(),
                timestamp(execution.executedAt()));
        // Like submission claims, a losing INSERT can wait for a row invisible to
        // its original statement snapshot. Read the committed winner in a new statement.
        return jdbc.queryForObject("""
                SELECT execution_id, order_id, quantity, price, executed_at
                FROM execution_receipts WHERE execution_id = ?
                """, (rs, row) -> new ExecutionClaim(rs.getObject("order_id", UUID.class),
                        new Execution(rs.getObject("execution_id", UUID.class), rs.getBigDecimal("quantity"),
                                rs.getBigDecimal("price"), instant(rs, "executed_at")), inserted == 1),
                execution.id());
    }

    @Override
    public Optional<StoredOrder> find(UUID id) {
        // One statement gives the order snapshot and all facts the same MVCC snapshot.
        return jdbc.query(SELECT_EVENTS + " WHERE o.id = ? ORDER BY e.sequence",
                this::readOrders, id).stream().findFirst();
    }

    @Override
    public List<StoredOrder> list(UUID accountId, int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) {
            throw new IllegalArgumentException("Limit must be 1..100 and offset 0..10000");
        }
        // Page orders before joining their histories, never a page of event rows.
        return jdbc.query("""
                WITH page AS (
                    SELECT id FROM orders WHERE account_id = ?
                    ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                )
                """ + SELECT_EVENTS + """
                 JOIN page p ON p.id = o.id
                 ORDER BY o.created_at DESC, o.id DESC, e.sequence
                """, this::readOrders, accountId, limit, offset);
    }

    @Override
    public StoredOrder insert(Order order) {
        requireTransaction();
        validatePersistable(order);
        jdbc.update("""
                INSERT INTO orders(id, account_id, account_name, account_active,
                    instrument_id, instrument_symbol, currency, tick_size, quantity_step,
                    side, order_type, quantity, limit_price, status, filled_quantity,
                    version, revision, last_sequence, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
                """, order.id(), order.account().id(), order.account().name(), order.account().active(),
                order.instrument().id(), order.instrument().symbol(), order.instrument().currency().getCurrencyCode(),
                order.instrument().tickSize(), order.instrument().quantityStep(), order.side().name(),
                order.type().name(), order.quantity(), order.limitPrice().orElse(null), order.status().name(),
                order.filledQuantity(), order.revision(), order.events().size(),
                timestamp(order.events().getFirst().occurredAt()), timestamp(order.events().getLast().occurredAt()));
        for (OrderEvent event : order.events()) appendEvent(event);
        return new StoredOrder(order, 0);
    }

    @Override
    public StoredOrder update(Order order, long expectedVersion) {
        requireTransaction();
        if (expectedVersion < 0) throw new IllegalArgumentException("Version cannot be negative");
        validatePersistable(order);
        StoredOrder previous = find(order.id()).orElseThrow(ConcurrentOrderUpdateException::new);
        if (previous.version() != expectedVersion) throw new ConcurrentOrderUpdateException();
        requireImmutablePrefix(previous.order(), order);
        int changed = jdbc.update("""
                UPDATE orders SET status = ?, filled_quantity = ?, version = version + 1,
                    revision = ?, last_sequence = ?, updated_at = ?
                WHERE id = ? AND version = ? AND last_sequence = ?
                """, order.status().name(), order.filledQuantity(), order.revision(), order.events().size(),
                timestamp(order.events().getLast().occurredAt()), order.id(), expectedVersion,
                previous.order().events().size());
        if (changed != 1) throw new ConcurrentOrderUpdateException();
        appendEvent(order.events().getLast());
        return new StoredOrder(order, Math.addExact(expectedVersion, 1));
    }

    private void appendEvent(OrderEvent event) {
        Execution execution = event.execution();
        jdbc.update("""
                INSERT INTO order_events(order_id, sequence, event_type, previous_status, status,
                    occurred_at, cumulative_quantity, execution_id, execution_quantity,
                    execution_price, executed_at, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, event.orderId(), event.sequence(), event.type().name(),
                event.previousStatus() == null ? null : event.previousStatus().name(), event.status().name(),
                timestamp(event.occurredAt()), event.cumulativeQuantity(),
                execution == null ? null : execution.id(), execution == null ? null : execution.quantity(),
                execution == null ? null : execution.price(),
                execution == null ? null : timestamp(execution.executedAt()), event.reason());
        // This is a durable reference to immutable data, not a claim of external delivery.
        jdbc.update("""
                INSERT INTO outbox_events(event_id, order_id, sequence, schema_version, created_at)
                VALUES (?, ?, ?, 1, ?)
                """, event.id(), event.orderId(), event.sequence(), timestamp(event.occurredAt()));
        jdbc.update("INSERT INTO outbox_delivery(event_id) VALUES (?)", event.id());
    }

    private List<StoredOrder> readOrders(ResultSet rs) throws SQLException {
        LinkedHashMap<UUID, OrderRows> orders = new LinkedHashMap<>();
        while (rs.next()) {
            UUID id = rs.getObject("id", UUID.class);
            OrderRows rows = orders.get(id);
            if (rows == null) {
                rows = new OrderRows(rs);
                orders.put(id, rows);
            }
            if (rs.getObject("event_sequence") != null) rows.events.add(readEvent(rs, id));
        }
        List<StoredOrder> result = new ArrayList<>();
        for (OrderRows rows : orders.values()) result.add(rows.restore());
        return List.copyOf(result);
    }

    private static OrderEvent readEvent(ResultSet rs, UUID orderId) throws SQLException {
        UUID executionId = rs.getObject("execution_id", UUID.class);
        Execution execution = executionId == null ? null : new Execution(executionId,
                rs.getBigDecimal("execution_quantity"), rs.getBigDecimal("execution_price"),
                instant(rs, "executed_at"));
        String previous = rs.getString("previous_status");
        return new OrderEvent(orderId, rs.getLong("event_sequence"),
                OrderEventType.valueOf(rs.getString("event_type")),
                previous == null ? null : OrderStatus.valueOf(previous),
                OrderStatus.valueOf(rs.getString("event_status")), instant(rs, "occurred_at"),
                rs.getBigDecimal("cumulative_quantity"), execution, rs.getString("reason"));
    }

    private static void requireImmutablePrefix(Order previous, Order next) {
        if (!previous.account().equals(next.account()) || !previous.instrument().equals(next.instrument())
                || previous.side() != next.side() || previous.type() != next.type()
                || !previous.quantity().equals(next.quantity()) || !previous.limitPrice().equals(next.limitPrice())
                || next.events().size() != previous.events().size() + 1
                || !next.events().subList(0, previous.events().size()).equals(previous.events())) {
            throw new IllegalArgumentException("Updates must preserve terms and append exactly one lifecycle event");
        }
    }

    private static void validatePersistable(Order order) {
        Objects.requireNonNull(order, "order");
        for (OrderEvent event : order.events()) {
            timestamp(event.occurredAt());
            if (event.execution() != null) timestamp(event.execution().executedAt());
        }
    }

    private static Timestamp timestamp(Instant instant) {
        if (instant.getNano() % 1000 != 0) {
            throw new IllegalArgumentException("Stored timestamps must have at most microsecond precision");
        }
        return Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.hasResource(Objects.requireNonNull(jdbc.getDataSource()))) {
            throw new IllegalStateException("Mutations require an active writable transaction");
        }
    }

    private static final class OrderRows {
        private final UUID id;
        private final Account account;
        private final Instrument instrument;
        private final Side side;
        private final OrderType type;
        private final BigDecimal quantity;
        private final BigDecimal limitPrice;
        private final OrderStatus status;
        private final BigDecimal filledQuantity;
        private final long version;
        private final long revision;
        private final long lastSequence;
        private final Instant createdAt;
        private final Instant updatedAt;
        private final List<OrderEvent> events = new ArrayList<>();

        private OrderRows(ResultSet rs) throws SQLException {
            id = rs.getObject("id", UUID.class);
            account = new Account(rs.getObject("account_id", UUID.class), rs.getString("account_name"),
                    rs.getBoolean("account_active"));
            instrument = new Instrument(rs.getObject("instrument_id", UUID.class),
                    rs.getString("instrument_symbol"), Currency.getInstance(rs.getString("currency")),
                    rs.getBigDecimal("tick_size"), rs.getBigDecimal("quantity_step"));
            side = Side.valueOf(rs.getString("side"));
            type = OrderType.valueOf(rs.getString("order_type"));
            quantity = rs.getBigDecimal("quantity");
            limitPrice = rs.getBigDecimal("limit_price");
            status = OrderStatus.valueOf(rs.getString("status"));
            filledQuantity = rs.getBigDecimal("filled_quantity");
            version = rs.getLong("version");
            revision = rs.getLong("revision");
            lastSequence = rs.getLong("last_sequence");
            createdAt = instant(rs, "created_at");
            updatedAt = instant(rs, "updated_at");
        }

        private StoredOrder restore() {
            Order order = Order.rehydrate(id, account, instrument, side, type, quantity, limitPrice, events);
            if (order.status() != status || order.filledQuantity().compareTo(filledQuantity) != 0
                    || order.revision() != revision || order.events().size() != lastSequence
                    || !order.events().getFirst().occurredAt().equals(createdAt)
                    || !order.events().getLast().occurredAt().equals(updatedAt)) {
                throw new IllegalStateException("Persisted order snapshot disagrees with immutable history: " + id);
            }
            return new StoredOrder(order, version);
        }
    }
}
