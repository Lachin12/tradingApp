package io.github.lachin12.oms.application.outbox;

import io.github.lachin12.oms.application.UnitOfWork;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Claims one message immediately before delivery. Claim and completion transactions are separate;
 * publication never runs in a database transaction. No transport or scheduler is installed by this class.
 */
public final class OutboxDispatcher {
    private final OutboxStore store;
    private final UnitOfWork transactions;
    private final EventPublisher publisher;
    private final Supplier<UUID> tokens;
    private final Duration lease;
    private final RetryPolicy retry;
    private final TransactionContext transactionContext;

    public OutboxDispatcher(OutboxStore store, UnitOfWork transactions, EventPublisher publisher,
                            Supplier<UUID> tokens, Duration lease, RetryPolicy retry,
                            TransactionContext transactionContext) {
        this.store = Objects.requireNonNull(store, "store");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.lease = OutboxDurations.wholeMilliseconds(lease, Duration.ofSeconds(1),
                Duration.ofMinutes(5), "Lease");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.transactionContext = Objects.requireNonNull(transactionContext, "transactionContext");
    }

    public DispatchResult dispatch(int runLimit) {
        requireNoActiveTransaction();
        if (runLimit < 1 || runLimit > 100) {
            throw new IllegalArgumentException("Run limit must be 1..100");
        }
        int claimed = 0;
        int published = 0;
        int rescheduled = 0;
        int leaseLost = 0;
        for (int index = 0; index < runLimit; index++) {
            UUID token = Objects.requireNonNull(tokens.get(), "Generated fencing token");
            List<DeliveryClaim> claims = transactions.execute(() -> {
                List<DeliveryClaim> result = List.copyOf(store.claim(1, lease, token));
                if (result.size() > 1) throw new IllegalStateException("Store returned more than one claim");
                return result;
            });
            if (claims.isEmpty()) break;
            DeliveryClaim claim = claims.getFirst();
            claimed++;
            // A transaction adapter could accidentally join or resume an ambient transaction.
            requireNoActiveTransaction();
            try {
                publisher.publish(claim.message());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new OutboxDispatchInterruptedException(interrupted);
            } catch (Exception publicationFailure) {
                Duration delay = retry.delayFor(claim.attempt());
                if (transactions.execute(() -> store.reschedule(claim, delay))) rescheduled++;
                else leaseLost++;
                continue;
            }
            if (transactions.execute(() -> store.acknowledge(claim))) published++;
            else leaseLost++;
        }
        return new DispatchResult(claimed, published, rescheduled, leaseLost);
    }

    private void requireNoActiveTransaction() {
        if (transactionContext.active()) {
            throw new IllegalStateException("Outbox publication requires no active database transaction");
        }
    }
}
