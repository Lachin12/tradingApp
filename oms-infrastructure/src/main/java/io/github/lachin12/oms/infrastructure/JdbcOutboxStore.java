package io.github.lachin12.oms.infrastructure;

import io.github.lachin12.oms.application.outbox.DeliveryClaim;
import io.github.lachin12.oms.application.outbox.OutboxMessage;
import io.github.lachin12.oms.application.outbox.OutboxStore;
import io.github.lachin12.oms.domain.Execution;
import io.github.lachin12.oms.domain.OrderEvent;
import io.github.lachin12.oms.domain.OrderEventType;
import io.github.lachin12.oms.domain.OrderStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Short database transactions lease immutable events; publishing happens outside this adapter.
 * Fencing protects bookkeeping, not an expired worker's already-running external side effect.
 */
public final class JdbcOutboxStore implements OutboxStore {
    private static final Duration MIN_LEASE = Duration.ofSeconds(1);
    private static final Duration MAX_LEASE = Duration.ofMinutes(5);
    private static final Duration MIN_RETRY = Duration.ofMillis(1);
    private static final Duration MAX_RETRY = Duration.ofHours(1);
    private final JdbcTemplate jdbc;

    public JdbcOutboxStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<DeliveryClaim> claim(int limit, Duration lease, UUID token) {
        requireTransaction();
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Claim limit must be 1..100");
        validateDuration(lease, MIN_LEASE, MAX_LEASE, "Lease");
        Objects.requireNonNull(token, "claim token");
        return List.copyOf(jdbc.query("""
                WITH candidates AS MATERIALIZED (
                    SELECT d.event_id
                    FROM outbox_delivery d
                    JOIN outbox_events envelope ON envelope.event_id = d.event_id
                    WHERE d.published_at IS NULL
                      AND d.next_attempt_at <= statement_timestamp()
                      AND (d.lease_until IS NULL OR d.lease_until <= statement_timestamp())
                      AND NOT EXISTS (
                          SELECT 1 FROM outbox_events earlier
                          LEFT JOIN outbox_delivery earlier_delivery
                            ON earlier_delivery.event_id = earlier.event_id
                          WHERE earlier.order_id = envelope.order_id
                            AND earlier.sequence < envelope.sequence
                            AND earlier_delivery.published_at IS NULL
                      )
                    ORDER BY d.next_attempt_at, envelope.created_at, envelope.order_id, envelope.sequence
                    LIMIT ? FOR UPDATE OF d SKIP LOCKED
                ), claimed AS (
                    UPDATE outbox_delivery d
                    SET attempts = d.attempts + 1, claim_token = ?,
                        lease_until = clock_timestamp() + CAST(? AS INTERVAL)
                    FROM candidates c WHERE d.event_id = c.event_id
                    RETURNING d.event_id, d.claim_token, d.attempts, d.lease_until
                )
                SELECT claimed.claim_token, claimed.attempts, claimed.lease_until,
                       envelope.schema_version, fact.*
                FROM claimed
                JOIN outbox_events envelope ON envelope.event_id = claimed.event_id
                JOIN order_events fact
                  ON fact.order_id = envelope.order_id AND fact.sequence = envelope.sequence
                ORDER BY envelope.created_at, envelope.order_id, envelope.sequence
                """, (rs, row) -> readClaim(rs), limit, token, interval(lease)));
    }

    @Override
    public boolean acknowledge(DeliveryClaim claim) {
        requireTransaction();
        Objects.requireNonNull(claim, "claim");
        if (!lockDelivery(claim.message().eventId())) return false;
        // Sample time only AFTER the row lock is acquired. A timestamp frozen before
        // a lock wait could incorrectly authorize a lease that expired during that wait.
        return jdbc.update("""
                WITH db_clock AS MATERIALIZED (SELECT clock_timestamp() AS at)
                UPDATE outbox_delivery d
                SET published_at = db_clock.at, claim_token = NULL, lease_until = NULL, last_error = NULL
                FROM db_clock
                WHERE d.event_id = ? AND d.published_at IS NULL
                  AND d.claim_token = ? AND d.attempts = ? AND d.lease_until > db_clock.at
                """, claim.message().eventId(), claim.token(), claim.attempt()) == 1;
    }

    @Override
    public boolean reschedule(DeliveryClaim claim, Duration delay) {
        requireTransaction();
        Objects.requireNonNull(claim, "claim");
        validateDuration(delay, MIN_RETRY, MAX_RETRY, "Retry delay");
        if (!lockDelivery(claim.message().eventId())) return false;
        return jdbc.update("""
                WITH db_clock AS MATERIALIZED (SELECT clock_timestamp() AS at)
                UPDATE outbox_delivery d
                SET claim_token = NULL, lease_until = NULL,
                    next_attempt_at = db_clock.at + CAST(? AS INTERVAL)
                FROM db_clock
                WHERE d.event_id = ? AND d.published_at IS NULL
                  AND d.claim_token = ? AND d.attempts = ? AND d.lease_until > db_clock.at
                """, interval(delay), claim.message().eventId(), claim.token(), claim.attempt()) == 1;
    }

    private boolean lockDelivery(String eventId) {
        return !jdbc.query("SELECT event_id FROM outbox_delivery WHERE event_id = ? FOR UPDATE",
                (rs, row) -> rs.getString("event_id"), eventId).isEmpty();
    }

    private static DeliveryClaim readClaim(ResultSet rs) throws SQLException {
        UUID executionId = rs.getObject("execution_id", UUID.class);
        Execution execution = executionId == null ? null : new Execution(executionId,
                rs.getBigDecimal("execution_quantity"), rs.getBigDecimal("execution_price"),
                rs.getTimestamp("executed_at").toInstant());
        String previous = rs.getString("previous_status");
        OrderEvent event = new OrderEvent(rs.getObject("order_id", UUID.class), rs.getLong("sequence"),
                OrderEventType.valueOf(rs.getString("event_type")),
                previous == null ? null : OrderStatus.valueOf(previous),
                OrderStatus.valueOf(rs.getString("status")), rs.getTimestamp("occurred_at").toInstant(),
                rs.getBigDecimal("cumulative_quantity"), execution, rs.getString("reason"));
        return new DeliveryClaim(new OutboxMessage(event, rs.getInt("schema_version")),
                rs.getObject("claim_token", UUID.class), rs.getInt("attempts"),
                rs.getTimestamp("lease_until").toInstant());
    }

    private static void validateDuration(Duration value, Duration minimum, Duration maximum, String name) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0
                || value.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException(name + " must be whole milliseconds in range "
                    + minimum.toMillis() + ".." + maximum.toMillis());
        }
    }

    private static String interval(Duration value) {
        // Only validated, bounded integer durations reach PostgreSQL. No floating point.
        return value.toMillis() + " milliseconds";
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.hasResource(Objects.requireNonNull(jdbc.getDataSource()))) {
            throw new IllegalStateException("Outbox mutations require an active writable transaction");
        }
    }
}
