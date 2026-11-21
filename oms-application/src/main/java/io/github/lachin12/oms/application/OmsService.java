package io.github.lachin12.oms.application;

import io.github.lachin12.oms.domain.*;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Use-case orchestration; framework and database types stay outside this boundary. */
public final class OmsService {
    private final ReferenceData references;
    private final OrderStore orders;
    private final SubmissionStore submissions;
    private final UnitOfWork transactions;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public OmsService(ReferenceData references, OrderStore orders, SubmissionStore submissions,
                      UnitOfWork transactions, Clock clock, Supplier<UUID> ids) {
        this.references = Objects.requireNonNull(references);
        this.orders = Objects.requireNonNull(orders);
        this.submissions = Objects.requireNonNull(submissions);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        this.ids = Objects.requireNonNull(ids);
    }

    public SubmissionResult submit(SubmitOrder request, String key) {
        if (request == null) throw new InvalidOrderRequestException("Request is required");
        validateKey(key);
        String fingerprint = RequestFingerprint.of(request);
        return transactions.execute(() -> {
            SubmissionClaim claim = submissions.claim(request.accountId(), key, fingerprint, ids.get());
            if (!claim.fingerprint().equals(fingerprint)) throw new IdempotencyConflictException();
            if (!claim.owner()) return new SubmissionResult(requireOrder(claim.orderId()), true);
            Account account = references.findAccount(request.accountId())
                    .orElseThrow(() -> new NotFoundException("Account not found"));
            Instrument instrument = references.findInstrument(request.instrumentId())
                    .orElseThrow(() -> new NotFoundException("Instrument not found"));
            Instant at = now();
            Order accepted = domainCommand(() -> Order.submit(claim.orderId(), account, instrument, request.side(),
                    request.type(), request.quantity(), request.limitPrice(), at).accept(at));
            return new SubmissionResult(orders.insert(accepted), false);
        });
    }

    public StoredOrder get(UUID orderId) {
        requireId(orderId);
        return transactions.execute(() -> requireOrder(orderId));
    }

    public List<StoredOrder> list(UUID accountId, int limit, int offset) {
        requireId(accountId);
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) {
            throw new InvalidOrderRequestException("Limit must be 1..100 and offset 0..10000");
        }
        return transactions.execute(() -> List.copyOf(orders.list(accountId, limit, offset)));
    }

    public StoredOrder cancel(UUID orderId, long expectedVersion, String reason) {
        requireId(orderId);
        requireVersion(expectedVersion);
        return transactions.execute(() -> {
            StoredOrder stored = requireVersion(requireOrder(orderId), expectedVersion);
            Instant at = atLeastLastEvent(stored.order());
            Order cancelled = domainCommand(() -> stored.order().cancel(reason, at));
            return orders.update(cancelled, expectedVersion);
        });
    }

    /** Future execution adapters use this boundary. There is deliberately no public fill REST route. */
    public StoredOrder recordExecution(UUID orderId, long expectedVersion, Execution execution) {
        requireId(orderId);
        requireVersion(expectedVersion);
        if (execution == null) throw new InvalidOrderRequestException("Execution is required");
        Execution normalized = domainCommand(() -> new Execution(execution.id(), execution.quantity(), execution.price(),
                execution.executedAt().truncatedTo(ChronoUnit.MICROS)));
        return transactions.execute(() -> {
            StoredOrder stored = requireVersion(requireOrder(orderId), expectedVersion);
            Instant at = atLeastLastEvent(stored.order());
            Order filled = domainCommand(() -> stored.order().fill(normalized, at));
            return orders.update(filled, expectedVersion);
        });
    }

    private StoredOrder requireOrder(UUID id) {
        return orders.find(id).orElseThrow(() -> new NotFoundException("Order not found"));
    }

    private static StoredOrder requireVersion(StoredOrder stored, long expected) {
        if (stored.version() != expected) throw new ConcurrentOrderUpdateException();
        return stored;
    }

    private Instant now() {
        // PostgreSQL timestamp precision is microseconds; truncate before creating domain facts.
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private Instant atLeastLastEvent(Order order) {
        Instant current = now();
        Instant last = order.events().getLast().occurredAt();
        return current.isBefore(last) ? last : current;
    }

    private static void requireId(UUID id) {
        if (id == null) throw new InvalidOrderRequestException("ID is required");
    }

    private static void requireVersion(long version) {
        if (version < 0) throw new InvalidOrderRequestException("Expected version cannot be negative");
    }

    private static void validateKey(String key) {
        if (key == null || key.length() < 1 || key.length() > 128
                || key.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new InvalidOrderRequestException("Idempotency key must contain 1..128 visible ASCII characters");
        }
    }

    /** Only local domain operations belong here; port and transaction failures must propagate unchanged. */
    private static <T> T domainCommand(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (IllegalArgumentException ex) {
            throw new InvalidOrderRequestException(ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new OrderConflictException(ex.getMessage(), ex);
        }
    }
}
