package io.github.lachin12.oms.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.lachin12.oms.domain.Account;
import io.github.lachin12.oms.domain.Execution;
import io.github.lachin12.oms.domain.Instrument;
import io.github.lachin12.oms.domain.Order;
import io.github.lachin12.oms.domain.OrderStatus;
import io.github.lachin12.oms.domain.OrderType;
import io.github.lachin12.oms.domain.Side;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ExecutionReportsTest {
    private static final UUID ORDER_ID = new UUID(0, 1);
    private static final UUID ACCOUNT_ID = new UUID(0, 2);
    private static final UUID INSTRUMENT_ID = new UUID(0, 3);
    private static final UUID REPORT_ID = new UUID(0, 4);
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00.123456Z");
    private final TrackingTransactions transactions = new TrackingTransactions();
    private final Map<UUID, ExecutionClaim> receipts = new HashMap<>();
    private StoredOrder stored;
    private OmsService service;
    private int reads;
    private int claims;
    private int updates;
    private RuntimeException receiptFailure;
    private RuntimeException readFailure;
    private RuntimeException updateFailure;

    @BeforeEach
    void setUp() {
        stored = new StoredOrder(accepted(), 0);
        // Backed mocks assert participation in the use-case transaction and expose observable writes.
        // This fixture deliberately does not emulate database rollback or establish database race safety.
        OrderStore orders = mock(OrderStore.class, invocation -> switch (invocation.getMethod().getName()) {
            case "find" -> {
                assertTrue(transactions.active);
                reads++;
                if (readFailure != null) throw readFailure;
                yield stored == null ? Optional.empty() : Optional.of(stored);
            }
            case "update" -> {
                assertTrue(transactions.active);
                updates++;
                if (updateFailure != null) throw updateFailure;
                long expected = invocation.getArgument(1);
                assertEquals(stored.version(), expected);
                stored = new StoredOrder(invocation.getArgument(0), expected + 1);
                yield stored;
            }
            default -> throw new AssertionError("Unexpected order-store operation: " + invocation.getMethod());
        });
        ExecutionStore executions = mock(ExecutionStore.class, invocation -> {
            assertEquals("claimExecution", invocation.getMethod().getName());
            assertTrue(transactions.active);
            claims++;
            if (receiptFailure != null) throw receiptFailure;
            UUID orderId = invocation.getArgument(0);
            Execution report = invocation.getArgument(1);
            ExecutionClaim previous = receipts.get(report.id());
            if (previous != null) return previous;
            receipts.put(report.id(), new ExecutionClaim(orderId, report, false));
            return new ExecutionClaim(orderId, report, true);
        });
        service = new OmsService(mock(ReferenceData.class), orders, mock(SubmissionStore.class), executions,
                transactions, Clock.fixed(NOW, ZoneOffset.UTC), () -> new UUID(0, 999));
    }

    @Test
    void newExecutionPersistsReceiptAndUpdatedHistoryInsideOneTransaction() {
        Execution report = report("3", "99.99");

        ExecutionResult result = service.reportExecution(ORDER_ID, 0, report);

        assertFalse(result.replay());
        assertEquals(1, result.storedOrder().version());
        assertEquals(OrderStatus.PARTIALLY_FILLED, result.storedOrder().order().status());
        assertEquals(new BigDecimal("3.00000000"), result.storedOrder().order().filledQuantity());
        assertEquals(report, result.storedOrder().order().events().getLast().execution());
        assertEquals(1, receipts.size());
        assertEquals(1, updates);
        assertEquals(1, transactions.commits);
    }

    @Test
    void duplicateAfterFullFillReturnsCurrentSnapshotDespiteStaleExpectedVersion() {
        Execution first = report("3", "99.99");
        service.reportExecution(ORDER_ID, 0, first);
        service.reportExecution(ORDER_ID, 1,
                new Execution(new UUID(0, 5), decimal("7"), decimal("100"), NOW));
        StoredOrder terminal = stored;

        ExecutionResult replay = service.reportExecution(ORDER_ID, 0, first);

        assertTrue(replay.replay());
        assertSame(terminal, replay.storedOrder());
        assertEquals(OrderStatus.FILLED, replay.storedOrder().order().status());
        assertEquals(2, updates);
        assertEquals(4, stored.order().events().size());
    }

    @Test
    void duplicateAfterCancellationReturnsCurrentSnapshotWithoutApplyingAnotherFill() {
        Execution first = report("3", "99.99");
        service.reportExecution(ORDER_ID, 0, first);
        StoredOrder cancelled = service.cancel(ORDER_ID, 1, "User request");

        ExecutionResult replay = service.reportExecution(ORDER_ID, 0, first);

        assertTrue(replay.replay());
        assertSame(cancelled, replay.storedOrder());
        assertEquals(OrderStatus.CANCELLED, replay.storedOrder().order().status());
        assertEquals(new BigDecimal("3.00000000"), replay.storedOrder().order().filledQuantity());
        assertEquals(2, updates);
    }

    @Test
    void equivalentDecimalsAndTimestampsWithinSameMicrosecondReplay() {
        Execution first = new Execution(REPORT_ID, decimal("3.0"), decimal("100.0"), NOW.plusNanos(100));
        Execution retry = new Execution(REPORT_ID, decimal("3.000000000"), decimal("100.000000000"), NOW.plusNanos(999));
        service.reportExecution(ORDER_ID, 0, first);

        ExecutionResult replay = service.reportExecution(ORDER_ID, 0, retry);

        assertTrue(replay.replay());
        assertEquals(NOW, stored.order().events().getLast().execution().executedAt());
        assertEquals(1, updates);
    }

    @ParameterizedTest
    @MethodSource("changedReports")
    void changedPayloadConflictsBeforeExpectedVersionOrOrderRead(Execution changed) {
        receipts.put(REPORT_ID, new ExecutionClaim(ORDER_ID, report("3", "100"), false));

        assertThrows(ExecutionConflictException.class, () -> service.reportExecution(ORDER_ID, 999, changed));

        assertEquals(0, reads);
        assertEquals(0, updates);
        assertEquals(1, transactions.rollbacks);
    }

    static Stream<Execution> changedReports() {
        return Stream.of(report("4", "100"), report("3", "99.99"),
                new Execution(REPORT_ID, decimal("3"), decimal("100"), NOW.plusNanos(1_000)));
    }

    @Test
    void reportIdentityCannotBeReusedForDifferentOrder() {
        receipts.put(REPORT_ID, new ExecutionClaim(ORDER_ID, report("3", "100"), false));

        assertThrows(ExecutionConflictException.class,
                () -> service.reportExecution(new UUID(0, 999), 0, report("3", "100")));
        assertEquals(0, reads);
        assertEquals(0, updates);
    }

    @Test
    void historyPayloadMismatchAlsoConflictsWhenClaimIsNew() {
        Order partial = accepted().fill(report("3", "100"), NOW);
        stored = new StoredOrder(partial, 1);

        assertThrows(ExecutionConflictException.class,
                () -> service.reportExecution(ORDER_ID, 1, report("4", "100")));
        assertEquals(0, updates);
    }

    @Test
    void existingReceiptWithoutMatchingLifecycleFactIsInternalInvariantFailure() {
        receipts.put(REPORT_ID, new ExecutionClaim(ORDER_ID, report("3", "100"), false));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.reportExecution(ORDER_ID, 0, report("3", "100")));

        assertFalse(failure instanceof OrderConflictException);
        assertEquals(0, updates);
    }

    @Test
    void staleNewReportFailsTheTransactionCallbackWithoutUpdatingOrder() {
        stored = new StoredOrder(accepted(), 3);

        assertThrows(ConcurrentOrderUpdateException.class,
                () -> service.reportExecution(ORDER_ID, 2, report("1", "100")));

        assertEquals(0, updates);
        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.rollbacks);
    }

    @Test
    void newExecutionAfterCancellationIsBusinessConflict() {
        stored = new StoredOrder(accepted().cancel("User request", NOW), 1);

        assertThrows(OrderConflictException.class, () -> service.reportExecution(ORDER_ID, 1, report("1", "100")));
        assertEquals(0, updates);
    }

    @Test
    void futureExecutionTimestampIsRejectedWithoutUpdating() {
        Execution future = new Execution(REPORT_ID, decimal("1"), decimal("100"), NOW.plusNanos(1_000));

        assertThrows(InvalidOrderRequestException.class, () -> service.reportExecution(ORDER_ID, 0, future));
        assertEquals(0, updates);
        assertEquals(1, transactions.rollbacks);
    }

    @Test
    void invalidExecutionTermsAreTypedValidationFailures() {
        assertThrows(InvalidOrderRequestException.class,
                () -> service.reportExecution(ORDER_ID, 0, report("0.15", "100")));
        assertEquals(0, updates);
    }

    @Test
    void missingExecutionInputsFailBeforeTransaction() {
        assertThrows(InvalidOrderRequestException.class, () -> service.reportExecution(null, 0, report("1", "100")));
        assertThrows(InvalidOrderRequestException.class, () -> service.reportExecution(ORDER_ID, -1, report("1", "100")));
        assertThrows(InvalidOrderRequestException.class, () -> service.reportExecution(ORDER_ID, 0, null));
        assertEquals(0, transactions.executions);
    }

    @ParameterizedTest
    @MethodSource("outOfRangeTimes")
    void timestampOutsideTradingRangeFailsBeforeClaimOrTransaction(Instant executionTime) {
        Execution report = new Execution(REPORT_ID, decimal("1"), decimal("100"), executionTime);

        assertThrows(InvalidOrderRequestException.class, () -> service.reportExecution(ORDER_ID, 0, report));

        assertEquals(0, claims);
        assertEquals(0, reads);
        assertEquals(0, transactions.executions);
    }

    static Stream<Instant> outOfRangeTimes() {
        return Stream.of(Instant.MIN, Instant.MAX, Instant.parse("0000-12-31T23:59:59Z"),
                Instant.parse("+300000-01-01T00:00:00Z"));
    }

    @Test
    void missingOrderDoesNotProduceAnExecutionUpdate() {
        stored = null;
        assertThrows(NotFoundException.class, () -> service.reportExecution(ORDER_ID, 0, report("1", "100")));
        assertEquals(0, updates);
        assertEquals(1, transactions.rollbacks);
    }

    @ParameterizedTest
    @MethodSource("portFailures")
    void receiptFailuresPropagateUnchanged(RuntimeException internal) {
        receiptFailure = internal;
        assertSame(internal, assertThrows(RuntimeException.class,
                () -> service.reportExecution(ORDER_ID, 0, report("1", "100"))));
        assertEquals(0, reads);
    }

    @ParameterizedTest
    @MethodSource("portFailures")
    void readFailuresPropagateUnchanged(RuntimeException internal) {
        readFailure = internal;
        assertSame(internal, assertThrows(RuntimeException.class,
                () -> service.reportExecution(ORDER_ID, 0, report("1", "100"))));
        assertEquals(0, updates);
    }

    @ParameterizedTest
    @MethodSource("portFailures")
    void updateFailuresPropagateUnchanged(RuntimeException internal) {
        updateFailure = internal;
        assertSame(internal, assertThrows(RuntimeException.class,
                () -> service.reportExecution(ORDER_ID, 0, report("1", "100"))));
        assertEquals(0, transactions.commits);
    }

    static Stream<RuntimeException> portFailures() {
        return Stream.of(new IllegalArgumentException("Internal storage argument"),
                new IllegalStateException("Internal storage state"));
    }

    private static Order accepted() {
        Account account = new Account(ACCOUNT_ID, "Simulation account", true);
        Instrument instrument = new Instrument(INSTRUMENT_ID, "ACME", Currency.getInstance("USD"),
                decimal("0.01"), decimal("0.1"));
        return Order.submit(ORDER_ID, account, instrument, Side.BUY, OrderType.LIMIT,
                decimal("10"), decimal("100"), NOW.minusSeconds(2)).accept(NOW.minusSeconds(1));
    }

    private static Execution report(String quantity, String price) {
        return new Execution(REPORT_ID, decimal(quantity), decimal(price), NOW);
    }

    private static BigDecimal decimal(String value) { return new BigDecimal(value); }

    private static final class TrackingTransactions implements UnitOfWork {
        private boolean active;
        private int executions;
        private int commits;
        private int rollbacks;

        @Override public <T> T execute(Supplier<T> work) {
            assertFalse(active);
            executions++;
            active = true;
            try {
                T result = work.get();
                commits++;
                return result;
            } catch (RuntimeException | Error failure) {
                rollbacks++;
                throw failure;
            } finally {
                active = false;
            }
        }
    }
}
